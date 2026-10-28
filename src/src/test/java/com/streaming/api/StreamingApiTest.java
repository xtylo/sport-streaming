package com.streaming.api;

import com.streaming.StreamingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice test: starts only MVC (controllers, JSON, validation, exception handler)
 * plus the real StreamingService. No Tomcat, no Kafka.
 * Each test uses its own matchId because the service (a singleton) is shared across tests.
 */
@WebMvcTest(controllers = {EventController.class, StreamController.class})
@Import(StreamingService.class)
class StreamingApiTest {

    @Autowired
    private MockMvc mvc;

    private static String newMatchId() {
        return "m-" + UUID.randomUUID();
    }

    private static String goalJson(String matchId, String team) {
        return """
                {"matchId":"%s","type":"GOAL","minute":23,"team":"%s"}
                """.formatted(matchId, team);
    }

    private void postEvent(String json) throws Exception {
        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isAccepted());
    }

    // ---------- POST /events ----------

    @Test
    @DisplayName("POST /events accepts a valid event and returns it with server defaults")
    void acceptsValidEvent() throws Exception {
        String matchId = newMatchId();

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(goalJson(matchId, "Tigres")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.matchId").value(matchId))
                .andExpect(jsonPath("$.type").value("GOAL"))
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.teamPresentWhenRequired").doesNotExist());
    }

    @Test
    @DisplayName("POST /events rejects an invalid event with Problem Details and per-field errors")
    void rejectsInvalidEvent() throws Exception {
        String invalid = """
                {"matchId":"","type":"GOAL","minute":200}
                """;

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Invalid match event"))
                .andExpect(jsonPath("$.errors.matchId").exists())
                .andExpect(jsonPath("$.errors.minute").exists())
                .andExpect(jsonPath("$.errors.teamPresentWhenRequired").exists());
    }

    @Test
    @DisplayName("POST /events rejects an unknown event type")
    void rejectsUnknownType() throws Exception {
        String unknownType = """
                {"matchId":"m1","type":"PENALTY_SHOOTOUT_DANCE","minute":10}
                """;

        mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content(unknownType))
                .andExpect(status().isBadRequest());
    }

    // ---------- GET /stream ----------

    @Test
    @DisplayName("GET /stream/{matchId} returns 404 for an unknown match")
    void unknownMatchIs404() throws Exception {
        mvc.perform(get("/stream/{matchId}", newMatchId()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /stream/{matchId} returns the updated score")
    void snapshotReflectsEvents() throws Exception {
        String matchId = newMatchId();
        postEvent(goalJson(matchId, "Tigres"));
        postEvent(goalJson(matchId, "Tigres"));

        mvc.perform(get("/stream/{matchId}", matchId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LIVE"))
                .andExpect(jsonPath("$.score.Tigres").value(2))
                .andExpect(jsonPath("$.recentEvents.length()").value(2));
    }

    @Test
    @DisplayName("GET /stream lists all known matches")
    void snapshotListsMatches() throws Exception {
        String matchId = newMatchId();
        postEvent(goalJson(matchId, "Rayados"));

        mvc.perform(get("/stream"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.matchId == '%s')]".formatted(matchId)).exists());
    }

    // ---------- GET /stream/live (SSE) ----------

    @Test
    @DisplayName("GET /stream/live pushes new events to the connected client")
    void sseClientReceivesEvent() throws Exception {
        String matchId = newMatchId();

        // 1. Client connects: the request goes async and stays open.
        MvcResult sse = mvc.perform(get("/stream/live").param("matchId", matchId))
                .andExpect(request().asyncStarted())
                .andReturn();

        // 2. An event is published while the client is connected.
        postEvent(goalJson(matchId, "Tigres"));

        // 3. The client's response stream contains the SSE frame.
        String body = sse.getResponse().getContentAsString();
        assertThat(sse.getResponse().getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(body)
                .contains("event:match-event")
                .contains("\"matchId\":\"" + matchId + "\"")
                .contains("\"team\":\"Tigres\"");
    }

    @Test
    @DisplayName("GET /stream/live sends the current snapshot to late subscribers")
    void lateSubscriberGetsSnapshot() throws Exception {
        String matchId = newMatchId();
        postEvent(goalJson(matchId, "Tigres"));

        MvcResult sse = mvc.perform(get("/stream/live").param("matchId", matchId))
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(sse.getResponse().getContentAsString())
                .startsWith("event:snapshot")
                .contains("\"Tigres\":1");
    }

    @Test
    @DisplayName("GET /stream/live requires a matchId")
    void sseRequiresMatchId() throws Exception {
        mvc.perform(get("/stream/live"))
                .andExpect(status().isBadRequest());
    }
}
