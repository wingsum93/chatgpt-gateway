package com.ericdream.gateway.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import tools.jackson.annotation.JsonProperty

@Service
class OpenRouterProxyService(
    @Qualifier("openRouterWebClient")
    private val openRouterWebClient: WebClient,
    @Value("\${app.openrouter.api-key}") private val openRouterApiKey: String,
    @Value("\${app.openrouter.test-model:openai/gpt-4o-mini}") configuredTestModel: String = "openai/gpt-4o-mini"
) {
    private val testModel: String = configuredTestModel.trim()

    init {
        require(openRouterApiKey.isNotBlank() && !openRouterApiKey.contains("\${")) {
            "app.openrouter.api-key must be configured with a real OpenRouter key"
        }
        require(testModel.isNotBlank()) {
            "app.openrouter.test-model must not be blank"
        }
    }

    fun forwardTestRequest(request: ServerHttpRequest): Mono<UpstreamBufferedResponse> {
        return openRouterWebClient
            .post()
            .uri("/api/v1/chat/completions")
            .headers { outboundHeaders ->
                applyOutboundHeaders(request, outboundHeaders)
            }
            .bodyValue(buildTestPayload())
            .exchangeToMono { clientResponse ->
                clientResponse.bodyToMono(ByteArray::class.java)
                    .defaultIfEmpty(ByteArray(0))
                    .map { responseBytes ->
                        UpstreamBufferedResponse(
                            statusCode = clientResponse.statusCode(),
                            headers = filterResponseHeaders(clientResponse.headers().asHttpHeaders()),
                            body = responseBytes
                        )
                    }
            }
    }

    internal fun buildTestPayload(): OpenRouterChatCompletionRequest {
        return OpenRouterChatCompletionRequest(
            model = testModel,
            messages = listOf(OpenRouterMessage(role = "user", content = "reply with: ok")),
            maxTokens = 8
        )
    }

    private fun applyOutboundHeaders(request: ServerHttpRequest, outboundHeaders: HttpHeaders) {
        outboundHeaders.setBearerAuth(openRouterApiKey)
        outboundHeaders.contentType = MediaType.APPLICATION_JSON
        copyIfPresent(request.headers, outboundHeaders, HttpHeaders.ACCEPT)
        copyIfPresent(request.headers, outboundHeaders, "Idempotency-Key")
        applyIpHeaders(request, outboundHeaders)
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
        copyIfPresent(upstreamHeaders, headers, "X-Request-Id")
        copyIfPresent(upstreamHeaders, headers, HttpHeaders.RETRY_AFTER)
        return headers
    }

    private fun copyIfPresent(source: HttpHeaders, target: HttpHeaders, name: String) {
        val value = source.getFirst(name) ?: return
        target.set(name, value)
    }

    internal data class OpenRouterChatCompletionRequest(
        val model: String,
        val messages: List<OpenRouterMessage>,
        @JsonProperty("max_tokens")
        val maxTokens: Int
    )

    internal data class OpenRouterMessage(
        val role: String,
        val content: String
    )
}
