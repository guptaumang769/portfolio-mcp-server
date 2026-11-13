package com.umang.mcp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClients pointed at the two backends this MCP server fronts. Both are treated as opaque
 * HTTP services — the MCP server imports nothing from them, it just calls their REST APIs.
 */
@Configuration
public class WebClientConfig {

    @Bean
    WebClient bookMyShowClient(@Value("${backends.bookmyshow-base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    WebClient uberClient(@Value("${backends.uber-base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }
}
