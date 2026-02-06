package com.ericdream.gateway.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component


@Component
class MyVerifier {

    private var environment: Environment? = null

    @Autowired
    fun MyVerifier(environment: Environment) {
        this.environment = environment
    }

    fun checkEnvironmentKey() {
        val key = "app.openai.api-key"
        if (environment!!.containsProperty(key)) {
            println("The key '" + key + "' exists with value: " + environment?.getProperty(key))
        } else {
            println("The key '$key' does not exist.")
        }
    }
}