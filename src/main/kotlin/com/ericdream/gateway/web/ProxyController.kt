package com.ericdream.gateway.web

import com.ericdream.gateway.service.OpenAIProxyService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.http.server.reactive.ServerHttpResponse
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

@RestController
class ProxyController(
    private val proxy: OpenAIProxyService,
    private val rateLimiter: (String) -> Boolean = {true}
) {

    @PostMapping("/v1/responses")
    fun responses(req: ServerHttpRequest, resp: ServerHttpResponse): Mono<Void> {
        val clientKey = req.headers.getFirst("X-Client-Id") ?: "anon" // MVP: optional
        if (!rateLimiter(clientKey)) {
            resp.statusCode = HttpStatus.TOO_MANY_REQUESTS
            return resp.setComplete()
        }

        // If upstream is SSE, keep it streaming.
        resp.headers.contentType = MediaType.TEXT_EVENT_STREAM

        return resp.writeWith(proxy.forwardResponses(req))
    }
}
