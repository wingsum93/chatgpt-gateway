package com.ericdream.gateway.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

data class UpstreamProxyResponse(
    val statusCode: HttpStatusCode,
    val headers: HttpHeaders,
    val body: Flux<DataBuffer>
)

@Service
class OpenAIProxyService(
    private val openAiWebClient: WebClient,
    @Value("\${app.openai.api-key}") private val openAiApiKey: String
) {
    fun forwardResponses(request: ServerHttpRequest, body: ByteArray): Mono<UpstreamProxyResponse> {
        return openAiWebClient
            .post()
            .uri("/v1/responses")
            .headers { outboundHeaders ->
                outboundHeaders.setBearerAuth(openAiApiKey)
                outboundHeaders.contentType = MediaType.APPLICATION_JSON
                copyIfPresent(request.headers, outboundHeaders, HttpHeaders.ACCEPT)
                copyIfPresent(request.headers, outboundHeaders, "OpenAI-Organization")
                copyIfPresent(request.headers, outboundHeaders, "OpenAI-Project")
                copyIfPresent(request.headers, outboundHeaders, "Idempotency-Key")
                applyIpHeaders(request, outboundHeaders)
            }
            .bodyValue(body)
            .exchangeToMono { clientResponse ->
                Mono.just(
                    UpstreamProxyResponse(
                        statusCode = clientResponse.statusCode(),
                        headers = filterResponseHeaders(clientResponse.headers().asHttpHeaders()),
                        body = clientResponse.bodyToFlux<DataBuffer>()
                    )
                )
            }
    }

    private fun copyIfPresent(source: HttpHeaders, target: HttpHeaders, name: String) {
        val value = source.getFirst(name) ?: return
        target.set(name, value)
    }

    private fun applyIpHeaders(request: ServerHttpRequest, outboundHeaders: HttpHeaders) {
        val inboundXForwardedFor = request.headers.getFirst("X-Forwarded-For")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        val socketIp = request.remoteAddress?.address?.hostAddress

        val xForwardedFor = inboundXForwardedFor ?: socketIp
        if (!xForwardedFor.isNullOrBlank()) {
            outboundHeaders.set("X-Forwarded-For", xForwardedFor)
        }

        val xRealIp = inboundXForwardedFor
            ?.split(",")
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: socketIp
        if (!xRealIp.isNullOrBlank()) {
            outboundHeaders.set("X-Real-IP", xRealIp)
        }
    }

    private fun filterResponseHeaders(upstreamHeaders: HttpHeaders): HttpHeaders {
        val headers = HttpHeaders()
        copyIfPresent(upstreamHeaders, headers, HttpHeaders.CONTENT_TYPE)
        copyIfPresent(upstreamHeaders, headers, HttpHeaders.CONTENT_LENGTH)
        copyIfPresent(upstreamHeaders, headers, "OpenAI-Request-Id")
        copyIfPresent(upstreamHeaders, headers, "X-Request-Id")
        copyIfPresent(upstreamHeaders, headers, HttpHeaders.RETRY_AFTER)
        return headers
    }
}
