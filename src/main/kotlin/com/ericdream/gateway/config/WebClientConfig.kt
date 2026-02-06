package com.ericdream.gateway.config

import io.netty.channel.ChannelOption
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import reactor.netty.http.client.HttpClient


@Configuration
class WebClientConfig {

    @Bean
    fun webClientBuilder(): WebClient.Builder = WebClient.builder()
        .defaultHeader("User-Agent", "eric-gateway")

    @Bean("openAiWebClient")
    fun openAiWebClient(
        builder: WebClient.Builder,
        @Value("\${app.openai.base-url}") baseUrl: String,
        @Value("\${app.openai.http.connect-timeout-ms:30000}") connectTimeoutMs: Int,
        @Value("\${app.openai.http.response-timeout-ms:60000}") responseTimeoutMs: Long
    ): WebClient {
        val client = buildHttpClient(connectTimeoutMs, responseTimeoutMs)
        return builder.clone()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(client))
            .build()
    }

    @Bean("openAiStreamingWebClient")
    fun openAiStreamingWebClient(
        builder: WebClient.Builder,
        @Value("\${app.openai.base-url}") baseUrl: String,
        @Value("\${app.openai.http.connect-timeout-ms:30000}") connectTimeoutMs: Int,
        @Value("\${app.openai.http.stream-response-timeout-ms:0}") streamResponseTimeoutMs: Long
    ): WebClient {
        val client = buildHttpClient(connectTimeoutMs, streamResponseTimeoutMs)
        return builder.clone()
            .baseUrl(baseUrl)
            .clientConnector(ReactorClientHttpConnector(client))
            .build()
    }

    private fun buildHttpClient(connectTimeoutMs: Int, responseTimeoutMs: Long): HttpClient {
        require(connectTimeoutMs > 0) { "app.openai.http.connect-timeout-ms must be > 0" }
        require(responseTimeoutMs >= 0) { "response timeout must be >= 0" }

        var httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMs)
        if (responseTimeoutMs > 0) {
            httpClient = httpClient.responseTimeout(Duration.ofMillis(responseTimeoutMs))
        }
        return httpClient
    }
}
