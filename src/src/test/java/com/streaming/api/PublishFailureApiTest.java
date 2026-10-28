package com.streaming.api;

import com.streaming.EventPublishException;
import com.streaming.MatchEventPublisher;
import com.streaming.domain.MatchEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** When the event bus is down, POST /events must answer 503 + Retry-After, never 500. */
@WebMvcTest(controllers = EventController.class)
class PublishFailureApiTest {

    @Autowired
    private MockMvc mvc;

    // = replacing the service with a Moq mock in ConfigureTestServices
    @MockitoBean
    private MatchEventPublisher publisher;

    @Test
    @DisplayName("publish failure returns 503 Problem Details with Retry-After")
    void publishFailureIs503() throws Exception {
        doAnswer(invocation -> {
            MatchEvent event = invocation.getArgument(0);
            throw new EventPublishException(event, new RuntimeException("broker down"));
        }).when(publisher).publish(any());

        mvc.perform(post("/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"matchId":"m1","type":"KICKOFF","minute":0}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Event bus unavailable"));
    }
}
