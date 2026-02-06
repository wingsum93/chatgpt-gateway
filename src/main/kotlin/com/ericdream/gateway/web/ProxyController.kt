package com.ericdream.gateway.web

import com.ericdream.gateway.service.OpenAIProxyService
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets

@RestController
class ProxyController(
    private val proxy: OpenAIProxyService,
    private val objectMapper: ObjectMapper,
    private val rateLimiter: (String) -> Boolean = { true }
) {

    @PostMapping("/v1/responses")
    fun responses(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        return forwardValidated(req, resp, requireStream = false)
    }

    @PostMapping("/v1/responses/stream")
    fun streamResponses(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        return forwardValidated(req, resp, requireStream = true)
    }

    private fun forwardValidated(
        req: ServerHttpRequest,
        resp: ServerHttpResponse,
        requireStream: Boolean
    ): Mono<Void> {
        val clientKey = req.headers.getFirst("X-Client-Id") ?: "anon"
        if (!rateLimiter(clientKey)) {
            return writeJsonError(resp, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded")
        }

        val contentType = req.headers.contentType
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return writeJsonError(resp, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "content_type_must_be_application_json")
        }

        return readBody(req)
            .flatMap { body ->
                val payload = parseJson(body)
                    ?: return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, "invalid_json_body")
                val streamValidationError = validateStream(payload, requireStream)
                if (streamValidationError != null) {
                    return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, streamValidationError)
                }

                proxy.forwardResponses(req, body, requireStream)
                    .flatMap { upstream ->
                        resp.statusCode = upstream.statusCode
                        upstream.headers.forEach { name, values -> resp.headers.addAll(name, values) }
                        resp.writeWith(upstream.body)
                    }
                    .onErrorResume {
                        writeJsonError(resp, HttpStatus.BAD_GATEWAY, "upstream_request_failed")
                    }
            }
    }

    private fun readBody(req: ServerHttpRequest): Mono<ByteArray> {
        return DataBufferUtils.join(req.body)
            .switchIfEmpty(Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(ByteArray(0))))
            .map { buffer ->
                try {
                    ByteArray(buffer.readableByteCount()).also { bytes -> buffer.read(bytes) }
                } finally {
                    DataBufferUtils.release(buffer)
                }
            }
    }

    private fun parseJson(body: ByteArray): JsonNode? {
        if (body.isEmpty()) return null
        return try {
            objectMapper.readTree(body)
        } catch (_: Exception) {
            null
        }
    }

    private fun validateStream(payload: JsonNode, requireStream: Boolean): String? {
        if (payload.has("stream") && !payload.get("stream").isBoolean) {
            return "stream_must_be_boolean"
        }
        val streamEnabled = payload.get("stream")?.booleanValue() ?: false
        if (requireStream && !streamEnabled) {
            return "stream_must_be_true_for_stream_endpoint"
        }
        if (!requireStream && streamEnabled) {
            return "stream_must_be_false_for_non_stream_endpoint"
        }
        return null
    }

    private fun writeJsonError(resp: ServerHttpResponse, status: HttpStatus, error: String): Mono<Void> {
        if (resp.isCommitted) return Mono.empty()
        resp.statusCode = status
        resp.headers.contentType = MediaType.APPLICATION_JSON
        val payload = """{"error":"$error"}""".toByteArray(StandardCharsets.UTF_8)
        return resp.writeWith(Mono.just(resp.bufferFactory().wrap(payload)))
    }
}
