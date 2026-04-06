package com.example.battlesimulator.dto;

public record PlayerAction(
        String battleId,
        String playerId,       // "player1" or "player2"
        String actionType,     // "MOVE" or "SWITCH"
        String targetId,       // move name if MOVE
        Integer switchIndex    // team index if SWITCH (0-5)
) {}
