package com.ericdream.gateway.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.annotation.Qualifier
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
import org.slf4j.LoggerFactory

data class UpstreamProxyResponse(
    val statusCode: HttpStatusCode,
    val headers: HttpHeaders,
    val body: Flux<DataBuffer>
)

@Service
class OpenAIProxyService(
    @Qualifier("openAiWebClient")
    private val openAiWebClient: WebClient,
    @Qualifier("openAiStreamingWebClient")
    private val openAiStreamingWebClient: WebClient,
    @Value("\${app.openai.api-key}") private val openAiApiKey: String,
    @Value("\${app.openai.forward-client-scope-headers:false}")
    private val forwardClientScopeHeaders: Boolean = false,
    @Value("\${app.openai.organization:}") configuredOrganization: String = "",
    @Value("\${app.openai.project:}") configuredProject: String = ""
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val openAiOrganization: String? = configuredOrganization.trim().ifEmpty { null }
    private val openAiProject: String? = configuredProject.trim().ifEmpty { null }

    init {
        require(openAiApiKey.isNotBlank() && !openAiApiKey.contains("\${")) {
            "app.openai.api-key must be configured with a real OpenAI key"
        }
    }

    fun forwardResponses(
        request: ServerHttpRequest,
        body: ByteArray,
        streaming: Boolean
    ): Mono<UpstreamProxyResponse> {
        val scopeHeaders = resolveScopeHeaders(request.headers)
        val client = if (streaming) openAiStreamingWebClient else openAiWebClient
        return client
            .post()
            .uri("/v1/responses")
            .headers { outboundHeaders ->
                outboundHeaders.setBearerAuth(openAiApiKey)
                outboundHeaders.contentType = MediaType.APPLICATION_JSON
                copyIfPresent(request.headers, outboundHeaders, HttpHeaders.ACCEPT)
                scopeHeaders.organization?.let { outboundHeaders.set("OpenAI-Organization", it) }
                scopeHeaders.project?.let { outboundHeaders.set("OpenAI-Project", it) }
                copyIfPresent(request.headers, outboundHeaders, "Idempotency-Key")
                applyIpHeaders(request, outboundHeaders)
            }
            .bodyValue(body)
            .exchangeToMono { clientResponse ->
                val upstreamRequestId = clientResponse.headers().asHttpHeaders().getFirst("OpenAI-Request-Id")
                if (clientResponse.statusCode().value() == 401) {
                    log.warn(
                        "openai_401 rid={} upstream_rid={} scope_source={} org_set={} project_set={}",
                        request.headers.getFirst("X-Request-Id") ?: "-",
                        upstreamRequestId ?: "-",
                        scopeHeaders.source,
                        scopeHeaders.organization != null,
                        scopeHeaders.project != null
                    )
                }
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

    private fun resolveScopeHeaders(requestHeaders: HttpHeaders): ScopeHeaders {
        if (openAiOrganization != null || openAiProject != null) {
            return ScopeHeaders(
                source = "server_config",
                organization = openAiOrganization,
                project = openAiProject
            )
        }
        if (forwardClientScopeHeaders) {
            val org = requestHeaders.getFirst("OpenAI-Organization")?.trim()?.ifEmpty { null }
            val project = requestHeaders.getFirst("OpenAI-Project")?.trim()?.ifEmpty { null }
            val source = if (org != null || project != null) "client_forward" else "none"
            return ScopeHeaders(source = source, organization = org, project = project)
        }
        return ScopeHeaders(source = "none", organization = null, project = null)
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

    private data class ScopeHeaders(
        val source: String,
        val organization: String?,
        val project: String?
    )
}
