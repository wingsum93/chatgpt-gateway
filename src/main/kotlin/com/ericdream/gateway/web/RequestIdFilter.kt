package com.ericdream.gateway.web

import org.slf4j.LoggerFactory
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.util.UUID

@Component
class RequestIdFilter : WebFilter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val reqId = exchange.request.headers.getFirst("X-Request-Id") ?: UUID.randomUUID().toString()
        exchange.response.headers.add("X-Request-Id", reqId)

        val started = System.nanoTime()
        return chain.filter(exchange).doFinally {
            val ms = (System.nanoTime() - started) / 1_000_000
            val req: ServerHttpRequest = exchange.request
            log.info("rid={} {} {} status={} {}ms",
                reqId, req.method, req.path, exchange.response.statusCode?.value(), ms
            )
        }
    }
}
