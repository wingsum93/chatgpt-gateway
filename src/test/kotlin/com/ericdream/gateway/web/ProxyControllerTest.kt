package com.ericdream.gateway.web

import com.ericdream.gateway.service.OpenAIProxyService
import com.ericdream.gateway.service.OpenRouterProxyService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.http.codec.multipart.Part
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.http.server.reactive.MockServerHttpResponse
import org.springframework.util.LinkedMultiValueMap
import org.springframework.util.MultiValueMap
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ProxyControllerTest {

    @Test
    fun `responses rejects stream true payload`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.BAD_GATEWAY, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("upstream_request_failed"))
    }

    @Test
    fun `responses returns 504 when upstream times out`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) {
            Mono.error(TimeoutException("upstream timed out"))
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("upstream_timeout"))
    }

    @Test
    fun `openrouter test forwards status headers and body`() {
        val capturedOpenAiRequest = AtomicReference<ClientRequest>()
        val capturedOpenRouterRequest = AtomicReference<ClientRequest>()
        val controller = newController(
            capturedRequest = capturedOpenAiRequest,
            capturedOpenRouterRequest = capturedOpenRouterRequest,
            openAiExchange = { successJsonResponse() },
            openRouterExchange = {
                Mono.just(
                    ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                        .header("X-Request-Id", "or_req_123")
                        .body("""{"id":"chatcmpl_1"}""")
                        .build()
                )
            }
        )

        val req = MockServerHttpRequest.post("/openrouter/test")
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
            .header("X-Forwarded-For", "198.51.100.9, 10.0.0.1")
            .body("")
        val resp = MockServerHttpResponse()

        controller.openRouterTest(req, resp).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals(MediaType.APPLICATION_JSON_VALUE, resp.headers.getFirst(HttpHeaders.CONTENT_TYPE))
        assertEquals("or_req_123", resp.headers.getFirst("X-Request-Id"))
        assertTrue(resp.bodyAsString.block()!!.contains("chatcmpl_1"))
        assertEquals("/api/v1/chat/completions", capturedOpenRouterRequest.get().url().path)
        assertEquals("Bearer test-openrouter-key", capturedOpenRouterRequest.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
        assertEquals("198.51.100.9, 10.0.0.1", capturedOpenRouterRequest.get().headers().getFirst("X-Forwarded-For"))
        assertEquals("198.51.100.9", capturedOpenRouterRequest.get().headers().getFirst("X-Real-IP"))
    }

    @Test
    fun `openrouter test rejects invalid bearer token before calling upstream`() {
        val capturedOpenAiRequest = AtomicReference<ClientRequest>()
        val capturedOpenRouterRequest = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(
            capturedRequest = capturedOpenAiRequest,
            capturedOpenRouterRequest = capturedOpenRouterRequest,
            openAiExchange = { successJsonResponse() },
            openRouterExchange = {
                upstreamCalls.incrementAndGet()
                successJsonResponse()
            }
        )

        val req = MockServerHttpRequest.post("/openrouter/test")
            .header(HttpHeaders.AUTHORIZATION, bearerAuth("wrong-token"))
            .body("")
        val resp = MockServerHttpResponse()

        controller.openRouterTest(req, resp).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertEquals("Bearer", resp.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(capturedOpenRouterRequest.get())
    }

    @Test
    fun `openrouter test maps upstream timeout to 504`() {
        val capturedOpenAiRequest = AtomicReference<ClientRequest>()
        val capturedOpenRouterRequest = AtomicReference<ClientRequest>()
        val controller = newController(
            capturedRequest = capturedOpenAiRequest,
            capturedOpenRouterRequest = capturedOpenRouterRequest,
            openAiExchange = { successJsonResponse() },
            openRouterExchange = { Mono.error(TimeoutException("upstream timed out")) }
        )

        val req = MockServerHttpRequest.post("/openrouter/test")
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
            .body("")
        val resp = MockServerHttpResponse()

        controller.openRouterTest(req, resp).block()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("upstream_timeout"))
    }

    @Test
    fun `openrouter test maps non timeout upstream failure to 502`() {
        val capturedOpenAiRequest = AtomicReference<ClientRequest>()
        val capturedOpenRouterRequest = AtomicReference<ClientRequest>()
        val controller = newController(
            capturedRequest = capturedOpenAiRequest,
            capturedOpenRouterRequest = capturedOpenRouterRequest,
            openAiExchange = { successJsonResponse() },
            openRouterExchange = { Mono.error(RuntimeException("boom")) }
        )

        val req = MockServerHttpRequest.post("/openrouter/test")
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
            .body("")
        val resp = MockServerHttpResponse()

        controller.openRouterTest(req, resp).block()

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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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
            .header(HttpHeaders.AUTHORIZATION, bearerAuth())
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

    @Test
    fun `responses rejects invalid bearer token before calling upstream`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearerAuth("wrong-token"))
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertEquals("Bearer", resp.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `stream endpoint rejects invalid bearer token before calling upstream`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = MockServerHttpRequest.post("/v1/responses/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, bearerAuth("wrong-token"))
            .body("""{"model":"gpt-5","input":"hi","stream":true}""")
        val resp = MockServerHttpResponse()

        controller.streamResponses(req, resp).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertEquals("Bearer", resp.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `responses rejects missing authorization header`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertEquals("Bearer", resp.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `responses rejects non bearer authorization scheme`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.AUTHORIZATION, "Basic abc123")
            .body("""{"model":"gpt-5","input":"hi"}""")
        val resp = MockServerHttpResponse()

        controller.responses(req, resp).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertEquals("Bearer", resp.headers.getFirst(HttpHeaders.WWW_AUTHENTICATE))
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `audio transcriptions rejects invalid bearer token before calling upstream`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = audioRequest("/v1/audio/transcriptions", authToken = "wrong-token")
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts()),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.UNAUTHORIZED, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("unauthorized"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `audio transcriptions rejects non multipart content type`() {
        val captured = AtomicReference<ClientRequest>()
        val upstreamCalls = AtomicInteger(0)
        val controller = newController(captured) {
            upstreamCalls.incrementAndGet()
            successJsonResponse()
        }

        val req = audioRequest(
            "/v1/audio/transcriptions",
            contentType = MediaType.APPLICATION_JSON
        )
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts()),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("content_type_must_be_multipart_form_data"))
        assertEquals(0, upstreamCalls.get())
        assertNull(captured.get())
    }

    @Test
    fun `audio transcriptions requires file part`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val parts = audioParts(includeFile = false)
        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(parts),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("file_is_required"))
    }

    @Test
    fun `audio transcriptions requires model part`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val parts = audioParts(model = null)
        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(parts),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("model_is_required"))
    }

    @Test
    fun `audio transcriptions rejects unsupported file extension`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val parts = audioParts(fileName = "voice.ogg")
        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(parts),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("unsupported_file_format"))
    }

    @Test
    fun `audio transcriptions rejects files larger than 25mb`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val parts = audioParts(fileBytes = ByteArray(25 * 1024 * 1024 + 1))
        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()

        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(parts),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("file_too_large_max_25mb"))
    }

    @Test
    fun `audio translations accepts whisper only and rejects 4o models`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = audioRequest("/v1/audio/translations")
        val resp = MockServerHttpResponse()
        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts(model = "gpt-4o-transcribe")),
            ProxyController.AudioEndpoint.TRANSLATIONS
        ).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("unsupported_model"))
    }

    @Test
    fun `audio transcriptions accepts gpt-4o-mini-transcribe`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()
        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts(model = "gpt-4o-mini-transcribe")),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("/v1/audio/transcriptions", captured.get().url().path)
    }

    @Test
    fun `audio transcriptions validates response format by model`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()
        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts(model = "gpt-4o-transcribe", responseFormat = "vtt")),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.BAD_REQUEST, resp.statusCode)
        assertTrue(resp.bodyAsString.block()!!.contains("unsupported_response_format_for_model"))
    }

    @Test
    fun `audio transcriptions allows optional prompt pass through`() {
        val captured = AtomicReference<ClientRequest>()
        val controller = newController(captured) { successJsonResponse() }

        val req = audioRequest("/v1/audio/transcriptions")
        val resp = MockServerHttpResponse()
        controller.forwardValidatedAudio(
            req,
            resp,
            Mono.just(audioParts(prompt = "hello world", responseFormat = "json")),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.OK, resp.statusCode)
        assertEquals("/v1/audio/transcriptions", captured.get().url().path)
    }

    @Test
    fun `audio transcriptions maps upstream failures to gateway errors`() {
        val captured = AtomicReference<ClientRequest>()
        val timeoutController = newController(captured) { Mono.error(TimeoutException("upstream timed out")) }
        val req = audioRequest("/v1/audio/transcriptions")

        val timeoutResp = MockServerHttpResponse()
        timeoutController.forwardValidatedAudio(
            req,
            timeoutResp,
            Mono.just(audioParts()),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.GATEWAY_TIMEOUT, timeoutResp.statusCode)
        assertTrue(timeoutResp.bodyAsString.block()!!.contains("upstream_timeout"))

        val badGatewayController = newController(captured) { Mono.error(RuntimeException("boom")) }
        val badGatewayResp = MockServerHttpResponse()
        badGatewayController.forwardValidatedAudio(
            req,
            badGatewayResp,
            Mono.just(audioParts()),
            ProxyController.AudioEndpoint.TRANSCRIPTIONS
        ).block()

        assertEquals(HttpStatus.BAD_GATEWAY, badGatewayResp.statusCode)
        assertTrue(badGatewayResp.bodyAsString.block()!!.contains("upstream_request_failed"))
    }

    private fun newController(
        capturedRequest: AtomicReference<ClientRequest>,
        capturedOpenRouterRequest: AtomicReference<ClientRequest> = capturedRequest,
        forwardClientScopeHeaders: Boolean = false,
        configuredOrganization: String = "",
        configuredProject: String = "",
        internalApiKey: String = TEST_INTERNAL_API_KEY,
        openRouterExchange: ((ClientRequest) -> Mono<ClientResponse>)? = null,
        openAiExchange: (ClientRequest) -> Mono<ClientResponse> = { successJsonResponse() }
    ): ProxyController {
        val exchangeFunction = ExchangeFunction { request ->
            capturedRequest.set(request)
            openAiExchange(request)
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
        val resolvedOpenRouterExchange = openRouterExchange ?: openAiExchange
        val openRouterExchangeFunction = ExchangeFunction { request ->
            capturedOpenRouterRequest.set(request)
            resolvedOpenRouterExchange(request)
        }
        val openRouterClient = WebClient.builder().exchangeFunction(openRouterExchangeFunction).build()
        val openRouterProxy = OpenRouterProxyService(
            openRouterWebClient = openRouterClient,
            openRouterApiKey = "test-openrouter-key",
            configuredTestModel = "openai/gpt-4o-mini"
        )
        return ProxyController(proxy, openRouterProxy, jacksonObjectMapper(), internalApiKey)
    }

    private fun bearerAuth(token: String = TEST_INTERNAL_API_KEY): String = "Bearer $token"

    private fun audioRequest(
        path: String,
        authToken: String = TEST_INTERNAL_API_KEY,
        contentType: MediaType = MediaType.MULTIPART_FORM_DATA
    ): MockServerHttpRequest {
        return MockServerHttpRequest.post(path)
            .contentType(contentType)
            .header(HttpHeaders.AUTHORIZATION, bearerAuth(authToken))
            .body("")
    }

    private fun audioParts(
        model: String? = "whisper-1",
        includeFile: Boolean = true,
        fileName: String = "audio.mp3",
        fileBytes: ByteArray = "audio".toByteArray(),
        responseFormat: String? = null,
        prompt: String? = null
    ): MultiValueMap<String, Part> {
        val map = LinkedMultiValueMap<String, Part>()
        if (includeFile) {
            map.add("file", TestFilePart("file", fileName, fileBytes, MediaType.valueOf("audio/mpeg")))
        }
        if (model != null) {
            map.add("model", TestFormFieldPart("model", model))
        }
        if (responseFormat != null) {
            map.add("response_format", TestFormFieldPart("response_format", responseFormat))
        }
        if (prompt != null) {
            map.add("prompt", TestFormFieldPart("prompt", prompt))
        }
        return map
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

    companion object {
        private const val TEST_INTERNAL_API_KEY = "test-internal-api-key"
    }

    private class TestFilePart(
        private val partName: String,
        private val fileName: String,
        private val bytes: ByteArray,
        private val contentType: MediaType
    ) : FilePart {
        override fun name(): String = partName
        override fun filename(): String = fileName
        override fun headers(): HttpHeaders = HttpHeaders().apply { this.contentType = contentType }
        override fun content(): Flux<org.springframework.core.io.buffer.DataBuffer> {
            return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes.copyOf()))
        }
        override fun transferTo(dest: Path): Mono<Void> = Mono.empty()
    }

    private class TestFormFieldPart(
        private val partName: String,
        private val fieldValue: String
    ) : FormFieldPart {
        override fun name(): String = partName
        override fun value(): String = fieldValue
        override fun headers(): HttpHeaders = HttpHeaders().apply { contentType = MediaType.TEXT_PLAIN }
        override fun content(): Flux<org.springframework.core.io.buffer.DataBuffer> {
            val bytes = fieldValue.toByteArray()
            return Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))
        }
    }
}
