package com.umang.mcp.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import com.umang.mcp.http.BackendClient;

@Configuration
public class WebClientConfig {

    @Bean
    @Qualifier("bookMyShowClient")
    WebClient bookMyShowClient(@Value("${backends.bookmyshow-base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    @Qualifier("uberClient")
    WebClient uberClient(@Value("${backends.uber-base-url:http://localhost:8080}") String baseUrl) {
        return WebClient.builder().baseUrl(baseUrl).build();
    }

    @Bean
    @Qualifier("bookMyShowBackend")
    BackendClient bookMyShowBackend(@Qualifier("bookMyShowClient") WebClient webClient) {
        return new BackendClient(webClient, "BookMyShow");
    }

    @Bean
    @Qualifier("uberBackend")
    BackendClient uberBackend(@Qualifier("uberClient") WebClient webClient) {
        return new BackendClient(webClient, "Uber");
    }
}
