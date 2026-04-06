package com.example.battlesimulator.dto;

public record AuthResponse(
        boolean success,
        String message,
        String username,
        String token
) {}
