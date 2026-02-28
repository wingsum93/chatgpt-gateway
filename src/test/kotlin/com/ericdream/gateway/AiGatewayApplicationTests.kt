package com.ericdream.gateway

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest(
	properties = [
		"app.openai.api-key=test-api-key",
		"app.openrouter.api-key=test-openrouter-key",
		"app.security.internal-api-key=test-internal-api-key"
	]
)
class AiGatewayApplicationTests {

	@Test
	fun contextLoads() {
	}

}
