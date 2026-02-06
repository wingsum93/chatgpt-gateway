package com.ericdream.gateway.web

import com.ericdream.gateway.service.OpenAIProxyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class ProxyControllerTest {

    @Test
    fun `responses rejects stream true payload`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi","stream":true}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("stream_must_be_false_for_non_stream_endpoint"))
    }

    @Test
    fun `stream endpoint requires stream true`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi","stream":false}""")
        val resp = MockServerHttpResponse()

        controller.streamResponses(req, resp).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("stream_must_be_true_for_stream_endpoint"))
    }

    @Test
    fun `responses forwards status headers and body for non streaming`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) {
            Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .header("OpenAI-Request-Id", "req_abc")
                    .body("""{"error":"rate_limit"}""")
                    .build()
            )
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Forwarded-For", "198.51.100.9, 10.0.0.1")
            .header("OpenAI-Organization", "org_123")
            .header("OpenAI-Project", "proj_123")
            .header("Idempotency-Key", "idem_123")
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, resp.statusCode)
        assertEquals(MediaType.APPLICATION_JSON_VALUE, resp.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("req_abc", resp.headers.getFirst("OpenAI-Request-Id"))
        assertTrue(resp.bodyAsString.block()!!.contains("rate_limit"))

        assertEquals("198.51.100.9, 10.0.0.1", captured.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("198.51.100.9", captured.get().headers().getFirst("X-Real-IP"))
        assertNull(captured.get().headers().getFirst("OpenAI-Organization"))
        assertNull(captured.get().headers().getFirst("OpenAI-Project"))
        assertEquals("idem_123", captured.get().headers().getFirst("Idempotency-Key"))
    }

    @Test
    fun `stream endpoint falls back to socket ip when x forwarded for is missing`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .remoteAddress(InetSocketAddress("203.0.113.7", 51234))
            .body("""{"model":"gpt-5","input":"hi","stream":true}""")
        val resp = MockServerHttpResponse()

        controller.streamResponses(req, resp).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("203.0.113.7", captured.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("203.0.113.7", captured.get().headers().getFirst("X-Real-IP"))
    }

    @Test
    fun `responses returns 502 when upstream request fails`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) {
            Mono.error(RuntimeException("upstream unavailable"))
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.BAD_GATEWAY, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("upstream_request_failed"))
    }

    @Test
    fun `responses forwards inbound scope headers when legacy forwarding enabled`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(
            captured,
            forwardClientScopeHeaders = true
        ) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header("OpenAI-Organization", "org_inbound")
            .header("OpenAI-Project", "proj_inbound")
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("org_inbound", captured.get().headers().getFirst("OpenAI-Organization"))
        assertEquals("proj_inbound", captured.get().headers().getFirst("OpenAI-Project"))
    }

    @Test
    fun `responses uses server configured scope headers over inbound headers`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(
            captured,
            forwardClientScopeHeaders = true,
            configuredOrganization = "org_server",
            configuredProject = "proj_server"
        ) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header("OpenAI-Organization", "org_inbound")
            .header("OpenAI-Project", "proj_inbound")
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("org_server", captured.get().headers().getFirst("OpenAI-Organization"))
        assertEquals("proj_server", captured.get().headers().getFirst("OpenAI-Project"))
    }

    @Test
    fun `service fails fast when api key is missing`() {
        val webClient = WebClient.builder()
            .exchangeFunction { Mono.just(successClientResponse()) }
            .build()

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            OpenAIProxyService(
                openAiWebClient = webClient,
                openAiStreamingWebClient = webClient,
                openAiApiKey = ""
            )
        }
    }

    private fun newController(
        capturedRequest: AtomicReference<ClientRequest>,
        forwardClientScopeHeaders: Boolean = false,
        configuredOrganization: String = "",
        configuredProject: String = "",
        exchange: (ClientRequest) -> Mono<ClientResponse>
    ): ProxyController {
        val exchangeFunction = ExchangeFunction { request ->
            capturedRequest.set(request)
            exchange(request)
        }
        val webClient = WebClient.builder().exchangeFunction(exchangeFunction).build()
        val streamingWebClient = WebClient.builder().exchangeFunction(exchangeFunction).build()
        val proxy = OpenAIProxyService(
            openAiWebClient = webClient,
            openAiStreamingWebClient = streamingWebClient,
            openAiApiKey = "test-api-key",
            forwardClientScopeHeaders = forwardClientScopeHeaders,
            configuredOrganization = configuredOrganization,
            configuredProject = configuredProject
        )
        return ProxyController(proxy, jacksonObjectMapper())
    }

    private fun successJsonResponse(): Mono<ClientResponse> {
        return Mono.just(successClientResponse())
    }

    private fun successClientResponse(): ClientResponse {
        return ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .header("OpenAI-Request-Id", "req_ok")
            .body("""{"id":"resp_1"}""")
            .build()
    }
}
