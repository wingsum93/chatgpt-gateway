package com.ericdream.gateway.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux
import org.springframework.core.io.buffer.DataBuffer

@Service
class OpenAIProxyService(
    private val openAiWebClient: WebClient,
    @Value("\${app.openai.api-key}") private val openAiApiKey: String
) {
    fun forwardResponses(request: ServerHttpRequest): Flux<DataBuffer> {
        // Forward body as-is, preserve streaming behavior.
        val contentType = request.headers.contentType ?: MediaType.APPLICATION_JSON

        return openAiWebClient
            .post()
            .uri("/v1/responses")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $openAiApiKey")
            .header(HttpHeaders.CONTENT_TYPE, contentType.toString())
            // You may optionally pass through OpenAI-Organization / OpenAI-Project if you use them
            .body(BodyInserters.fromDataBuffers(request.body))
            .exchangeToFlux { clientResponse ->
                // pass through status code handling can be added here
                clientResponse.bodyToFlux<DataBuffer>()
            }
    }
}
