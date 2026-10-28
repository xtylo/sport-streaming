package com.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end through a real Kafka broker (Testcontainers):
 * POST /events -> KafkaMatchEventPublisher -> topic -> MatchEventConsumer -> StreamingService -> GET /stream.
 * <p>
 * Delivery is asynchronous, so assertions use Awaitility: "eventually, within N seconds".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class KafkaIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Value("${streaming.kafka.topic}")
    private String topic;

    private static String newMatchId() {
        return "it-" + UUID.randomUUID();
    }

    private void postGoal(String matchId, String team) throws Exception {
        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"matchId":"%s","type":"GOAL","minute":10,"team":"%s"}
                                """.formatted(matchId, team)))
                .andExpect(status().isAccepted());
    }

    @Test
    @DisplayName("an event posted over HTTP travels through Kafka and updates the match state")
    void eventTravelsThroughKafka() throws Exception {
        String matchId = newMatchId();

        postGoal(matchId, "Tigres");
        postGoal(matchId, "Tigres");

        await().atMost(TIMEOUT).untilAsserted(() ->
                mvc.perform(get("/stream/{matchId}", matchId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.score.Tigres").value(2)));
    }

    @Test
    @DisplayName("an unreadable record is skipped and does not block the partition")
    void poisonMessageIsSkipped() throws Exception {
        String matchId = newMatchId();

        // Same key as the valid event -> same partition. If the consumer got stuck on the
        // bad record, the valid event behind it would never be processed.
        kafkaTemplate.send(topic, matchId, "{ this is not json").get(10, TimeUnit.SECONDS);
        postGoal(matchId, "Rayados");

        await().atMost(TIMEOUT).untilAsserted(() ->
                mvc.perform(get("/stream/{matchId}", matchId))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.score.Rayados").value(1)));
    }
}
