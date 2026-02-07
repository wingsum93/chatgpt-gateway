package com.ericdream.gateway.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenAIProxyServiceRoutingTest {

    @Test
    fun `forwardResponses uses non streaming client when streaming flag is false`() {
        val nonStreamingCalls = AtomicInteger(0)
        val streamingCalls = AtomicInteger(0)
        val service = newService(
            nonStreamingExchange = {
                nonStreamingCalls.incrementAndGet()
                Mono.just(okResponse())
            },
            streamingExchange = {
                streamingCalls.incrementAndGet()
                Mono.just(okResponse())
            }
        )

        val request = MockServerHttpRequest.post("/v1/responses")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi"}""")
        service.forwardResponsesNonStream(request, """{"model":"gpt-5","input":"hi"}""".toByteArray()).block()

        assertEquals(1, nonStreamingCalls.get())
        assertEquals(0, streamingCalls.get())
    }

    @Test
    fun `forwardResponses uses streaming client when streaming flag is true`() {
        val nonStreamingCalls = AtomicInteger(0)
        val streamingCalls = AtomicInteger(0)
        val service = newService(
            nonStreamingExchange = {
                nonStreamingCalls.incrementAndGet()
                Mono.just(okResponse())
            },
            streamingExchange = {
                streamingCalls.incrementAndGet()
                Mono.just(okResponse())
            }
        )

        val request = MockServerHttpRequest.post("/v1/responses/stream")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"model":"gpt-5","input":"hi","stream":true}""")
        service.forwardResponsesStream(request, """{"model":"gpt-5","input":"hi","stream":true}""".toByteArray()).block()

        assertEquals(0, nonStreamingCalls.get())
        assertEquals(1, streamingCalls.get())
    }

    @Test
    fun `forwardAudioTranscriptions uses non streaming client and routes to transcription endpoint`() {
        val nonStreamingCalls = AtomicInteger(0)
        val streamingCalls = AtomicInteger(0)
        val capturedPath = AtomicReference<String>()
        val service = newService(
            nonStreamingExchange = {
                nonStreamingCalls.incrementAndGet()
                capturedPath.set(it.url().path)
                Mono.just(okResponse())
            },
            streamingExchange = {
                streamingCalls.incrementAndGet()
                Mono.just(okResponse())
            }
        )

        val request = MockServerHttpRequest.post("/v1/audio/transcriptions")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body("")
        service.forwardAudioTranscriptions(request, multipartBody()).block()

        assertEquals(1, nonStreamingCalls.get())
        assertEquals(0, streamingCalls.get())
        assertEquals("/v1/audio/transcriptions", capturedPath.get())
    }

    @Test
    fun `forwardAudioTranslations uses non streaming client and routes to translation endpoint`() {
        val nonStreamingCalls = AtomicInteger(0)
        val streamingCalls = AtomicInteger(0)
        val capturedPath = AtomicReference<String>()
        val service = newService(
            nonStreamingExchange = {
                nonStreamingCalls.incrementAndGet()
                capturedPath.set(it.url().path)
                Mono.just(okResponse())
            },
            streamingExchange = {
                streamingCalls.incrementAndGet()
                Mono.just(okResponse())
            }
        )

        val request = MockServerHttpRequest.post("/v1/audio/translations")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body("")
        service.forwardAudioTranslations(request, multipartBody()).block()

        assertEquals(1, nonStreamingCalls.get())
        assertEquals(0, streamingCalls.get())
        assertEquals("/v1/audio/translations", capturedPath.get())
    }

    private fun newService(
        nonStreamingExchange: ExchangeFunction,
        streamingExchange: ExchangeFunction
    ): OpenAIProxyService {
        val nonStreamingClient = WebClient.builder().exchangeFunction(nonStreamingExchange).build()
        val streamingClient = WebClient.builder().exchangeFunction(streamingExchange).build()
        return OpenAIProxyService(
            openAiWebClient = nonStreamingClient,
            openAiStreamingWebClient = streamingClient,
            openAiApiKey = "test-api-key"
        )
    }

    private fun okResponse(): ClientResponse {
        return ClientResponse.create(HttpStatus.OK)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .body("""{"id":"resp_1"}""")
            .build()
    }

    private fun multipartBody(): LinkedMultiValueMap<String, HttpEntity<*>> {
        val parts = LinkedMultiValueMap<String, HttpEntity<*>>()
        parts.add("model", HttpEntity("whisper-1"))
        parts.add("prompt", HttpEntity("hello"))
        return parts
    }
}
