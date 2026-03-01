package com.ericdream.gateway.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.mock.http.client.reactive.MockClientHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.reactive.function.BodyInserter
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.net.URI
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference

class OpenRouterProxyServiceTest {

    @Test
    fun `forwardTestRequest routes to chat completions with expected headers and payload`() {
        val capturedRequest = AtomicReference<ClientRequest>()
        val capturedBody = AtomicReference<String>()
        val service = newService { request ->
            capturedRequest.set(request)
            capturedBody.set(extractBody(request))
            Mono.just(okResponse())
        }

        val request = MockServerHttpRequest.post("/openrouter/test")
            .header("X-Forwarded-For", "198.51.100.9, 10.0.0.1")
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .header("Idempotency-Key", "idem_123")
            .body("")

        val response = service.forwardTestRequest(request).block()!!
        val payload = jacksonObjectMapper().readTree(capturedBody.get())

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("/api/v1/chat/completions", capturedRequest.get().url().path)
        assertEquals("Bearer test-openrouter-key", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        assertEquals(MediaType.APPLICATION_JSON_VALUE, capturedRequest.get().headers().getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("198.51.100.9, 10.0.0.1", capturedRequest.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("198.51.100.9", capturedRequest.get().headers().getFirst("X-Real-IP"))
        assertEquals("idem_123", capturedRequest.get().headers().getFirst("Idempotency-Key"))

        assertEquals("openai/gpt-4o-mini", payload.get("model").asText())
        assertEquals(8, payload.get("max_tokens").asInt())
        assertEquals(1, payload.get("messages").size())
        assertEquals("user", payload.get("messages").get(0).get("role").asText())
        assertEquals("reply with: ok", payload.get("messages").get(0).get("content").asText())
    }

    @Test
    fun `forwardTestRequest falls back to socket ip when x forwarded for is missing`() {
        val capturedRequest = AtomicReference<ClientRequest>()
        val service = newService { request ->
            capturedRequest.set(request)
            Mono.just(okResponse())
        }

        val request = MockServerHttpRequest.post("/openrouter/test")
            .remoteAddress(InetSocketAddress("203.0.113.7", 43210))
            .body("")

        service.forwardTestRequest(request).block()

        assertEquals("203.0.113.7", capturedRequest.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("203.0.113.7", capturedRequest.get().headers().getFirst("X-Real-IP"))
    }

    @Test
    fun `forwardTestRequest fails when api key is missing`() {
        val webClient = WebClient.builder()
            .exchangeFunction { Mono.just(okResponse()) }
            .build()

        val service = OpenRouterProxyService(
            openRouterWebClient = webClient,
            openRouterApiKey = "",
            configuredTestModel = "openai/gpt-4o-mini"
        )

        val request = MockServerHttpRequest.post("/openrouter/test").body("")
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            service.forwardTestRequest(request).block()
        }
    }

    @Test
    fun `service uses configured test model`() {
        val requestBody = AtomicReference<String>()
        val service = newService(testModel = "anthropic/claude-3.5-sonnet") { request ->
            requestBody.set(extractBody(request))
            Mono.just(okResponse())
        }

        val request = MockServerHttpRequest.post("/openrouter/test").body("")
        service.forwardTestRequest(request).block()

        val payload = jacksonObjectMapper().readTree(requestBody.get())
        assertEquals("anthropic/claude-3.5-sonnet", payload.get("model").asText())
    }

    @Test
    fun `forwardDictionaryRequest routes to chat completions and returns extracted content`() {
        val capturedRequest = AtomicReference<ClientRequest>()
        val capturedBody = AtomicReference<String>()
        val service = newService { request ->
            capturedRequest.set(request)
            capturedBody.set(extractBody(request))
            Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("X-Request-Id", "or_req_dict_1")
                    .body("""{"choices":[{"message":{"content":"蘋果"}}]}""")
                    .build()
            )
        }

        val request = MockServerHttpRequest.post("/openrouter/dictionary?model=openai/gpt-4o-mini&word=apple")
            .header("X-Forwarded-For", "198.51.100.9, 10.0.0.1")
            .header(HttpHeaders.ACCEPT, MediaType.TEXT_PLAIN_VALUE)
            .header("Idempotency-Key", "idem_456")
            .body("")

        val response = service.forwardDictionaryRequest(
            request = request,
            model = "openai/gpt-4o-mini",
            word = "apple"
        ).block()!!

        val payload = jacksonObjectMapper().readTree(capturedBody.get())
        assertEquals("/api/v1/chat/completions", capturedRequest.get().url().path)
        assertEquals("Bearer test-openrouter-key", capturedRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        assertEquals(MediaType.APPLICATION_JSON_VALUE, capturedRequest.get().headers().getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("198.51.100.9, 10.0.0.1", capturedRequest.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("198.51.100.9", capturedRequest.get().headers().getFirst("X-Real-IP"))
        assertEquals("idem_456", capturedRequest.get().headers().getFirst("Idempotency-Key"))
        assertEquals("or_req_dict_1", response.headers.getFirst("X-Request-Id"))
        assertEquals("蘋果", response.content)

        assertEquals("openai/gpt-4o-mini", payload.get("model").asText())
        assertEquals(128, payload.get("max_tokens").asInt())
        assertEquals(2, payload.get("messages").size())
        assertEquals("system", payload.get("messages").get(0).get("role").asText())
        assertTrue(payload.get("messages").get(0).get("content").asText().contains("繁體中文"))
        assertEquals("user", payload.get("messages").get(1).get("role").asText())
        assertEquals("apple", payload.get("messages").get(1).get("content").asText())
    }

    @Test
    fun `forwardDictionaryRequest fails when upstream response content is missing`() {
        val service = newService {
            Mono.just(
                ClientResponse.create(HttpStatus.OK)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .body("""{"id":"chatcmpl_1"}""")
                    .build()
            )
        }
        val request = MockServerHttpRequest.post("/openrouter/dictionary?model=openai/gpt-4o-mini&word=apple").body("")

        assertThrows(OpenRouterProxyService.InvalidUpstreamResponseException::class.java) {
            service.forwardDictionaryRequest(
                request = request,
                model = "openai/gpt-4o-mini",
                word = "apple"
            ).block()
        }
    }

    @Test
    fun `forwardDictionaryRequest fails when api key is missing`() {
        val webClient = WebClient.builder()
            .exchangeFunction { Mono.just(okResponse()) }
            .build()
        val service = OpenRouterProxyService(
            openRouterWebClient = webClient,
            openRouterApiKey = "",
            configuredTestModel = "openai/gpt-4o-mini"
        )

        val request = MockServerHttpRequest.post("/openrouter/dictionary?model=openai/gpt-4o-mini&word=apple").body("")
        assertThrows(IllegalStateException::class.java) {
            service.forwardDictionaryRequest(
                request = request,
                model = "openai/gpt-4o-mini",
                word = "apple"
            ).block()
        }
    }

    private fun newService(
        apiKey: String = "test-openrouter-key",
        testModel: String = "openai/gpt-4o-mini",
        exchange: ExchangeFunction
    ): OpenRouterProxyService {
        val webClient = WebClient.builder().exchangeFunction(exchange).build()
        return OpenRouterProxyService(
            openRouterWebClient = webClient,
            openRouterApiKey = apiKey,
            configuredTestModel = testModel
        )
    }

    private fun extractBody(request: ClientRequest): String {
        val httpRequest = MockClientHttpRequest(HttpMethod.POST, URI.create("https://openrouter.ai/api/v1/chat/completions"))
        request.body().insert(httpRequest, object : BodyInserter.Context {
            override fun messageWriters() = ExchangeStrategies.withDefaults().messageWriters()
            override fun serverRequest(): Optional<ServerHttpRequest> = Optional.empty()
            override fun hints(): Map<String, Any> = emptyMap()
        }).block()
        val body = httpRequest.bodyAsString.block()
        assertTrue(body != null)
        return body!!
    }

    private fun okResponse(): ClientResponse {
        return ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("X-Request-Id", "or_req_123")
            .body("""{"id":"chatcmpl_1"}""")
            .build()
    }
}
