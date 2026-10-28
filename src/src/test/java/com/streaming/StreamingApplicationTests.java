package com.streaming;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Smoke test: the full application context starts (against a Testcontainers Kafka). */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StreamingApplicationTests {

	@Test
	void contextLoads() {
	}

}
