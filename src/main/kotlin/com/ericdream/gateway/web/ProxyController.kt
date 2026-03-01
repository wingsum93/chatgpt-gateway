package com.ericdream.gateway.web

import com.ericdream.gateway.service.OpenAIProxyService
import com.ericdream.gateway.service.OpenRouterProxyService
import org.springframework.beans.factory.annotation.Value
import org.slf4j.LoggerFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.FormFieldPart
import org.springframework.http.codec.multipart.Part
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import io.netty.handler.timeout.ReadTimeoutException
import io.netty.handler.timeout.WriteTimeoutException

@RestController
class ProxyController(
    private val proxy: OpenAIProxyService,
    private val openRouterProxy: OpenRouterProxyService,
    private val objectMapper: ObjectMapper,
    @Value("\${app.security.internal-api-key}") private val internalApiKey: String,
    private val rateLimiter: (String) -> Boolean = { true }
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(internalApiKey.isNotBlank() && !internalApiKey.contains("\${")) {
            "app.security.internal-api-key must be configured with a real internal key"
        }
    }

    @PostMapping("/v1/responses", produces = ["application/json"], consumes = ["application/json"])
    fun responses(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        return forwardValidated(req, resp, requireStream = false)
    }

    @PostMapping("/v1/responses/stream", consumes = ["text/event-stream"])
    fun streamResponses(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        return forwardValidated(req, resp, requireStream = true)
    }

    @PostMapping("/v1/audio/transcriptions", consumes = ["multipart/form-data"])
    fun audioTranscriptions(exchange: ServerWebExchange): Mono<Void> {
        return forwardValidatedAudio(
            exchange.request,
            exchange.response,
            exchange.multipartData,
            AudioEndpoint.TRANSCRIPTIONS
        )
    }

    @PostMapping("/v1/audio/translations", consumes = ["multipart/form-data"])
    fun audioTranslations(exchange: ServerWebExchange): Mono<Void> {
        return forwardValidatedAudio(
            exchange.request,
            exchange.response,
            exchange.multipartData,
            AudioEndpoint.TRANSLATIONS
        )
    }

    @PostMapping("/openrouter/test", produces = ["application/json"])
    fun openRouterTest(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        if (!hasValidInternalBearer(req)) {
            log.warn("openrouter_test_rejected rid={} reason=unauthorized", requestId(req))
            return writeUnauthorized(resp)
        }

        val clientKey = req.headers.getFirst("X-Client-Id") ?: "anon"
        if (!rateLimiter(clientKey)) {
            log.warn(
                "openrouter_test_rejected rid={} reason=rate_limit_exceeded client_id={}",
                requestId(req),
                clientKey
            )
            return writeJsonError(resp, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded")
        }

        return openRouterProxy.forwardTestRequest(req)
            .flatMap { upstream ->
                resp.statusCode = upstream.statusCode
                upstream.headers.forEach { name, values -> resp.headers.addAll(name, values) }
                resp.writeWith(Mono.just(resp.bufferFactory().wrap(upstream.body)))
            }
            .onErrorResume { throwable ->
                mapUpstreamError(resp, throwable)
            }
    }

    private fun forwardValidated(
        req: ServerHttpRequest,
        resp: ServerHttpResponse,
        requireStream: Boolean
    ): Mono<Void> {
        if (!hasValidInternalBearer(req)) {
            return writeUnauthorized(resp)
        }

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

                if (requireStream) {
                    proxy.forwardResponsesStream(req, body)
                        .flatMap { upstream ->
                            resp.statusCode = upstream.statusCode
                            upstream.headers.forEach { name, values -> resp.headers.addAll(name, values) }
                            resp.writeWith(upstream.body)
                        }
                        .onErrorResume { throwable ->
                            mapUpstreamError(resp, throwable)
                        }
                } else {
                    proxy.forwardResponsesNonStream(req, body)
                        .flatMap { upstream ->
                            resp.statusCode = upstream.statusCode
                            upstream.headers.forEach { name, values -> resp.headers.addAll(name, values) }
                            resp.writeWith(Mono.just(resp.bufferFactory().wrap(upstream.body)))
                        }
                        .onErrorResume { throwable ->
                            mapUpstreamError(resp, throwable)
                        }
                }
            }
    }

    internal fun forwardValidatedAudio(
        req: ServerHttpRequest,
        resp: ServerHttpResponse,
        partsMono: Mono<MultiValueMap<String, Part>>,
        endpoint: AudioEndpoint
    ): Mono<Void> {
        if (!hasValidInternalBearer(req)) {
            return writeUnauthorized(resp)
        }

        val clientKey = req.headers.getFirst("X-Client-Id") ?: "anon"
        if (!rateLimiter(clientKey)) {
            return writeJsonError(resp, HttpStatus.TOO_MANY_REQUESTS, "rate_limit_exceeded")
        }

        val contentType = req.headers.contentType
        if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            return writeJsonError(resp, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "content_type_must_be_multipart_form_data")
        }

        return partsMono
            .flatMap { parts ->
                val unexpectedFileError = validateNoUnexpectedFileParts(parts)
                if (unexpectedFileError != null) {
                    return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, unexpectedFileError)
                }

                val filePart = when (val fileResult = extractRequiredSingleFilePart(parts)) {
                    is FieldResult.Error -> return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, fileResult.error)
                    is FieldResult.Success -> fileResult.value
                }

                val model = when (val modelResult = extractRequiredSingleFormField(parts, "model")) {
                    is FieldResult.Error -> return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, modelResult.error)
                    is FieldResult.Success -> modelResult.value
                }

                if (!endpoint.supportedModels.contains(model)) {
                    return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, "unsupported_model")
                }

                val responseFormat = when (val formatResult = extractOptionalSingleFormField(parts, "response_format")) {
                    is OptionalFieldResult.Error -> return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, formatResult.error)
                    is OptionalFieldResult.Missing -> null
                    is OptionalFieldResult.Success -> formatResult.value
                }
                if (responseFormat != null) {
                    if (responseFormat.isBlank()) {
                        return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, "unsupported_response_format_for_model")
                    }
                    val allowedFormats = MODEL_ALLOWED_RESPONSE_FORMATS[model] ?: emptySet()
                    if (!allowedFormats.contains(responseFormat)) {
                        return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, "unsupported_response_format_for_model")
                    }
                }

                val prompt = when (val promptResult = extractOptionalSingleFormField(parts, "prompt")) {
                    is OptionalFieldResult.Error -> return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, promptResult.error)
                    is OptionalFieldResult.Missing -> null
                    is OptionalFieldResult.Success -> promptResult.value
                }
                if (prompt != null) {
                    // Prompt is intentionally pass-through only; no semantic validation.
                }

                val extension = extractFileExtension(filePart.filename())
                if (extension == null || !SUPPORTED_AUDIO_EXTENSIONS.contains(extension)) {
                    return@flatMap writeJsonError(resp, HttpStatus.BAD_REQUEST, "unsupported_file_format")
                }

                readFileContent(filePart)
                    .flatMap { fileBytes ->
                        if (fileBytes.size > MAX_AUDIO_FILE_BYTES) {
                            return@flatMap writeJsonError(resp, HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large_max_25mb")
                        }

                        val multipartBody = rebuildMultipartBody(parts, filePart, fileBytes)
                        val forward = when (endpoint) {
                            AudioEndpoint.TRANSCRIPTIONS -> proxy.forwardAudioTranscriptions(req, multipartBody)
                            AudioEndpoint.TRANSLATIONS -> proxy.forwardAudioTranslations(req, multipartBody)
                        }

                        forward
                            .flatMap { upstream ->
                                resp.statusCode = upstream.statusCode
                                upstream.headers.forEach { name, values -> resp.headers.addAll(name, values) }
                                resp.writeWith(Mono.just(resp.bufferFactory().wrap(upstream.body)))
                            }
                            .onErrorResume { throwable ->
                                mapUpstreamError(resp, throwable)
                            }
                    }
            }
            .onErrorResume { throwable ->
                if (isRequestTooLargeError(throwable)) {
                    writeJsonError(resp, HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large_max_25mb")
                } else {
                    writeJsonError(resp, HttpStatus.BAD_REQUEST, "invalid_multipart_body")
                }
            }
    }

    private fun hasValidInternalBearer(req: ServerHttpRequest): Boolean {
        val authorization = req.headers.getFirst(HttpHeaders.AUTHORIZATION)?.trim() ?: return false
        if (!authorization.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            return false
        }
        val token = authorization.substring(BEARER_PREFIX.length).trim()
        return token.isNotEmpty() && token == internalApiKey
    }

    private fun requestId(req: ServerHttpRequest): String {
        return req.headers.getFirst("X-Request-Id")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "-"
    }

    private fun writeUnauthorized(resp: ServerHttpResponse): Mono<Void> {
        resp.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
        return writeJsonError(resp, HttpStatus.UNAUTHORIZED, "unauthorized")
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

    private fun mapUpstreamError(resp: ServerHttpResponse, throwable: Throwable): Mono<Void> {
        return if (isTimeoutError(throwable)) {
            writeJsonError(resp, HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout")
        } else {
            writeJsonError(resp, HttpStatus.BAD_GATEWAY, "upstream_request_failed")
        }
    }

    private fun validateNoUnexpectedFileParts(parts: MultiValueMap<String, Part>): String? {
        parts.forEach { (name, values) ->
            values.forEach { part ->
                if (part is FilePart && name != "file") {
                    return "unexpected_file_part"
                }
            }
        }
        return null
    }

    private fun extractRequiredSingleFilePart(parts: MultiValueMap<String, Part>): FieldResult<FilePart> {
        val candidates = parts["file"] ?: return FieldResult.Error("file_is_required")
        if (candidates.size != 1) return FieldResult.Error("file_must_be_single")
        val filePart = candidates.firstOrNull() as? FilePart ?: return FieldResult.Error("file_must_be_file_part")
        return FieldResult.Success(filePart)
    }

    private fun extractRequiredSingleFormField(parts: MultiValueMap<String, Part>, name: String): FieldResult<String> {
        val candidates = parts[name] ?: return FieldResult.Error("${name}_is_required")
        if (candidates.size != 1) return FieldResult.Error("${name}_must_be_single_text_field")
        val formField = candidates.firstOrNull() as? FormFieldPart ?: return FieldResult.Error("${name}_must_be_single_text_field")
        val value = formField.value().trim()
        if (value.isBlank()) {
            return FieldResult.Error("${name}_is_required")
        }
        return FieldResult.Success(value)
    }

    private fun extractOptionalSingleFormField(parts: MultiValueMap<String, Part>, name: String): OptionalFieldResult {
        val candidates = parts[name] ?: return OptionalFieldResult.Missing
        if (candidates.size != 1) return OptionalFieldResult.Error("${name}_must_be_single_text_field_when_present")
        val formField = candidates.firstOrNull() as? FormFieldPart
            ?: return OptionalFieldResult.Error("${name}_must_be_single_text_field_when_present")
        return OptionalFieldResult.Success(formField.value().trim())
    }

    private fun extractFileExtension(filename: String): String? {
        val trimmed = filename.trim()
        val dotIndex = trimmed.lastIndexOf('.')
        if (dotIndex <= 0 || dotIndex == trimmed.lastIndex) return null
        return trimmed.substring(dotIndex + 1).lowercase()
    }

    private fun readFileContent(filePart: FilePart): Mono<ByteArray> {
        return DataBufferUtils.join(filePart.content())
            .map { buffer ->
                try {
                    ByteArray(buffer.readableByteCount()).also { bytes -> buffer.read(bytes) }
                } finally {
                    DataBufferUtils.release(buffer)
                }
            }
    }

    private fun rebuildMultipartBody(
        parts: MultiValueMap<String, Part>,
        filePart: FilePart,
        fileBytes: ByteArray
    ): MultiValueMap<String, HttpEntity<*>> {
        val builder = MultipartBodyBuilder()
        val fileResource = object : ByteArrayResource(fileBytes) {
            override fun getFilename(): String = filePart.filename()
        }
        val fileBuilder = builder.part("file", fileResource)
        filePart.headers().contentType?.let { fileBuilder.contentType(it) }

        parts.forEach { (name, values) ->
            values.forEach { part ->
                if (part is FormFieldPart) {
                    builder.part(name, part.value())
                }
            }
        }
        return builder.build()
    }

    private fun isRequestTooLargeError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            val simpleName = current::class.simpleName?.lowercase()
            if (simpleName != null && simpleName.contains("databufferlimit")) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTimeoutError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            if (current is TimeoutException ||
                current is SocketTimeoutException ||
                current is ReadTimeoutException ||
                current is WriteTimeoutException
            ) {
                return true
            }
            val message = current.message?.lowercase()
            if (message != null && (message.contains("timeout") || message.contains("timed out"))) {
                return true
            }
            current = current.cause
        }
        return false
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val MAX_AUDIO_FILE_BYTES = 25 * 1024 * 1024
        private val SUPPORTED_AUDIO_EXTENSIONS = setOf("mp3", "mp4", "mpeg", "mpga", "m4a", "wav", "webm")
        private val MODEL_ALLOWED_RESPONSE_FORMATS = mapOf(
            "whisper-1" to setOf("json", "text", "srt", "verbose_json", "vtt"),
            "gpt-4o-transcribe" to setOf("json", "text"),
            "gpt-4o-mini-transcribe" to setOf("json", "text")
        )
    }

    enum class AudioEndpoint(val supportedModels: Set<String>) {
        TRANSCRIPTIONS(setOf("whisper-1", "gpt-4o-transcribe", "gpt-4o-mini-transcribe")),
        TRANSLATIONS(setOf("whisper-1"))
    }

    private sealed interface FieldResult<out T> {
        data class Success<T>(val value: T) : FieldResult<T>
        data class Error(val error: String) : FieldResult<Nothing>
    }

    private sealed interface OptionalFieldResult {
        data class Success(val value: String) : OptionalFieldResult
        data object Missing : OptionalFieldResult
        data class Error(val error: String) : OptionalFieldResult
    }
}
