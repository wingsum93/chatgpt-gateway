package com.ericdream.gateway.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeFunction
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.util.concurrent.atomic.AtomicInteger

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
        service.forwardResponses(request, """{"model":"gpt-5","input":"hi"}""".toByteArray(), false).block()

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
        service.forwardResponses(request, """{"model":"gpt-5","input":"hi","stream":true}""".toByteArray(), true).block()

        assertEquals(0, nonStreamingCalls.get())
        assertEquals(1, streamingCalls.get())
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
}
