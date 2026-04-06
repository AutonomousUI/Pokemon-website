package com.example.battlesimulator.dto;

import java.util.List;

public record LobbyStateResponse(
        String username,
        List<String> onlineUsers,
        List<ChallengeView> incomingChallenges,
        List<ChallengeView> outgoingChallenges,
        MatchView activeMatch
) {
    public record ChallengeView(
            String id,
            String fromUsername,
            String toUsername,
            String status
    ) {}

    public record MatchView(
            String challengeId,
            String battleId,
            String opponentUsername,
            String yourPlayerId
    ) {}
}
