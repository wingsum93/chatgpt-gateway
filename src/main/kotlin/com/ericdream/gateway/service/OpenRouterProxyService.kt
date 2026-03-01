package com.ericdream.gateway.service

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

@Service
class OpenRouterProxyService(
    @Qualifier("openRouterWebClient")
    private val openRouterWebClient: WebClient,
    @Value("\${app.openrouter.api-key}") private val openRouterApiKey: String,
    @Value("\${app.openrouter.test-model:openai/gpt-4o-mini}") configuredTestModel: String = "openai/gpt-4o-mini"
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val resolvedOpenRouterApiKey: String? = openRouterApiKey.trim()
        .takeIf { it.isNotEmpty() && !it.contains("\${") }
    private val testModel: String = configuredTestModel.trim()

    init {
        require(testModel.isNotBlank()) {
            "app.openrouter.test-model must not be blank"
        }
    }

    fun forwardTestRequest(request: ServerHttpRequest): Mono<UpstreamBufferedResponse> {
        val requestId = requestId(request.headers)
        val apiKey = resolvedOpenRouterApiKey
            ?: run {
                log.error("openrouter_config_error rid={} endpoint=openrouter_test reason=missing_api_key", requestId)
                return Mono.error(IllegalStateException("app.openrouter.api-key must be configured"))
            }
        return openRouterWebClient
            .post()
            .uri("/api/v1/chat/completions")
            .headers { outboundHeaders ->
                applyOutboundHeaders(request, outboundHeaders, apiKey)
            }
            .bodyValue(buildTestPayload())
            .exchangeToMono { clientResponse ->
                val responseHeaders = clientResponse.headers().asHttpHeaders()
                val status = clientResponse.statusCode().value()
                if (status >= 400) {
                    log.warn(
                        "openrouter_upstream_non_success rid={} endpoint=openrouter_test status={} upstream_rid={}",
                        requestId,
                        status,
                        requestId(responseHeaders)
                    )
                }
                clientResponse.bodyToMono(ByteArray::class.java)
                    .defaultIfEmpty(ByteArray(0))
                    .map { responseBytes ->
                        UpstreamBufferedResponse(
                            statusCode = clientResponse.statusCode(),
                            headers = filterResponseHeaders(responseHeaders),
                            body = responseBytes
                        )
                    }
            }
            .doOnError { throwable ->
                log.error(
                    "openrouter_request_failed rid={} endpoint=openrouter_test error_type={} error={}",
                    requestId,
                    throwable.javaClass.simpleName,
                    throwable.message ?: "-",
                    throwable
                )
            }
    }

    internal fun buildTestPayload(): OpenRouterChatCompletionRequest {
        return OpenRouterChatCompletionRequest(
            model = testModel,
            messages = listOf(OpenRouterMessage(role = "user", content = "reply with: ok")),
            max_tokens = 8
        )
    }

    private fun applyOutboundHeaders(request: ServerHttpRequest, outboundHeaders: HttpHeaders, apiKey: String) {
        outboundHeaders.setBearerAuth(apiKey)
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

    private fun requestId(headers: HttpHeaders): String {
        return headers.getFirst("X-Request-Id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "-"
    }

    internal data class OpenRouterChatCompletionRequest(
        val model: String,
        val messages: List<OpenRouterMessage>,
        val max_tokens: Int
    )

    internal data class OpenRouterMessage(
        val role: String,
        val content: String
    )
}
