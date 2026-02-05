package com.ericdream.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

@Configuration
class WebClientConfig {
    @Bean
    fun openAiWebClient(
        builder: WebClient.Builder,
        @Value("\${app.openai.base-url}") baseUrl: String
    ): WebClient {
        return builder
            .baseUrl(baseUrl)
            .build()
    }
}
