package com.example.battlesimulator.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // The server will broadcast turn results to endpoints starting with /topic
        // Example: Player 1 and 2 will subscribe to /topic/battle/123
        config.enableSimpleBroker("/topic");

        // When players lock in a move, they will send messages to /app
        // Example: They send a message to /app/action
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // This is the initial handshake URL where the frontend connects
        // setAllowedOriginPatterns("*") is crucial for testing from different ports (like a React/Vue frontend)
        registry.addEndpoint("/battle-ws")
                .setAllowedOriginPatterns("*")
                .withSockJS(); // Fallback for browsers that don't fully support WebSockets
    }
}
