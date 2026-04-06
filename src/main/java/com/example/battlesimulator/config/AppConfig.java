package com.example.battlesimulator.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class AppConfig {

    @Bean
    public RestClient pokeApiClient() {
        return RestClient.builder()
                .baseUrl("https://pokeapi.co/api/v2")
                .build();
    }
}