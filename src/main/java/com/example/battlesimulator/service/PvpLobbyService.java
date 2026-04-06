package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.LobbyStateResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PvpLobbyService {

    private final AuthService authService;
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    public PvpLobbyService(AuthService authService) {
        this.authService = authService;
    }

    public LobbyStateResponse getLobbyState(String username, String query) {
        return new LobbyStateResponse(
                username,
                authService.getOnlineUsernames(username, query),
                challenges.values().stream()
                        .filter(challenge -> challenge.status == ChallengeStatus.PENDING)
                        .filter(challenge -> challenge.toUsername.equalsIgnoreCase(username))
                        .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                        .map(this::toView)
                        .toList(),
                challenges.values().stream()
                        .filter(challenge -> challenge.fromUsername.equalsIgnoreCase(username))
                        .filter(challenge -> challenge.status != ChallengeStatus.COMPLETED)
                        .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                        .map(this::toView)
                        .toList(),
                challenges.values().stream()
                        .filter(challenge -> challenge.status == ChallengeStatus.ACCEPTED)
                        .filter(challenge -> challenge.fromUsername.equalsIgnoreCase(username)
                                || challenge.toUsername.equalsIgnoreCase(username))
                        .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                        .findFirst()
                        .map(challenge -> toMatchView(challenge, username))
                        .orElse(null)
        );
    }

    public void sendChallenge(String fromUsername, String toUsername) {
        if (toUsername == null || toUsername.isBlank()) {
            throw new IllegalArgumentException("Choose a username to challenge.");
        }
        if (fromUsername.equalsIgnoreCase(toUsername)) {
            throw new IllegalArgumentException("You can't challenge yourself.");
        }
        if (!authService.isOnline(toUsername)) {
            throw new IllegalArgumentException("That user is not online.");
        }
        boolean usersBusy = challenges.values().stream()
                .anyMatch(challenge -> challenge.status == ChallengeStatus.ACCEPTED
                        && (challenge.fromUsername.equalsIgnoreCase(fromUsername)
                        || challenge.toUsername.equalsIgnoreCase(fromUsername)
                        || challenge.fromUsername.equalsIgnoreCase(toUsername)
                        || challenge.toUsername.equalsIgnoreCase(toUsername)));
        if (usersBusy) {
            throw new IllegalArgumentException("One of those players is already in a battle.");
        }

        boolean pendingBetweenUsers = challenges.values().stream()
                .anyMatch(challenge -> challenge.status == ChallengeStatus.PENDING
                        && ((challenge.fromUsername.equalsIgnoreCase(fromUsername)
                        && challenge.toUsername.equalsIgnoreCase(toUsername))
                        || (challenge.fromUsername.equalsIgnoreCase(toUsername)
                        && challenge.toUsername.equalsIgnoreCase(fromUsername))));
        if (pendingBetweenUsers) {
            throw new IllegalArgumentException("A challenge between those users is already pending.");
        }

        Challenge challenge = new Challenge();
        challenge.id = UUID.randomUUID().toString();
        challenge.fromUsername = fromUsername;
        challenge.toUsername = toUsername;
        challenge.status = ChallengeStatus.PENDING;
        challenge.createdAt = System.currentTimeMillis();
        challenges.put(challenge.id, challenge);
    }

    public void acceptChallenge(String username, String challengeId) {
        Challenge challenge = getChallenge(challengeId);
        if (!challenge.toUsername.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("You can only accept your own incoming challenges.");
        }
        if (challenge.status != ChallengeStatus.PENDING) {
            throw new IllegalArgumentException("That challenge is no longer pending.");
        }
        clearOtherChallengesForUsers(challenge.fromUsername, challenge.toUsername, challenge.id);
        challenge.status = ChallengeStatus.ACCEPTED;
        challenge.battleId = "pvp-" + UUID.randomUUID();
    }

    public void rejectChallenge(String username, String challengeId) {
        Challenge challenge = getChallenge(challengeId);
        if (!challenge.toUsername.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("You can only reject your own incoming challenges.");
        }
        if (challenge.status == ChallengeStatus.PENDING) {
            challenge.status = ChallengeStatus.REJECTED;
        }
    }

    public void leaveActiveMatch(String username) {
        challenges.values().stream()
                .filter(challenge -> challenge.status == ChallengeStatus.ACCEPTED)
                .filter(challenge -> challenge.fromUsername.equalsIgnoreCase(username)
                        || challenge.toUsername.equalsIgnoreCase(username))
                .forEach(challenge -> challenge.status = ChallengeStatus.COMPLETED);
    }

    private Challenge getChallenge(String challengeId) {
        Challenge challenge = challenges.get(challengeId);
        if (challenge == null) {
            throw new IllegalArgumentException("Challenge not found.");
        }
        return challenge;
    }

    private void clearOtherChallengesForUsers(String usernameA, String usernameB, String acceptedId) {
        for (Challenge challenge : new ArrayList<>(challenges.values())) {
            boolean involvesAcceptedUsers = challenge.fromUsername.equalsIgnoreCase(usernameA)
                    || challenge.fromUsername.equalsIgnoreCase(usernameB)
                    || challenge.toUsername.equalsIgnoreCase(usernameA)
                    || challenge.toUsername.equalsIgnoreCase(usernameB);
            if (!challenge.id.equals(acceptedId) && involvesAcceptedUsers && challenge.status == ChallengeStatus.PENDING) {
                challenge.status = ChallengeStatus.REJECTED;
            }
        }
    }

    private LobbyStateResponse.ChallengeView toView(Challenge challenge) {
        return new LobbyStateResponse.ChallengeView(
                challenge.id,
                challenge.fromUsername,
                challenge.toUsername,
                challenge.status.name()
        );
    }

    private LobbyStateResponse.MatchView toMatchView(Challenge challenge, String username) {
        boolean challenger = challenge.fromUsername.equalsIgnoreCase(username);
        return new LobbyStateResponse.MatchView(
                challenge.id,
                challenge.battleId,
                challenger ? challenge.toUsername : challenge.fromUsername,
                challenger ? "player1" : "player2"
        );
    }

    private enum ChallengeStatus {
        PENDING,
        ACCEPTED,
        REJECTED,
        COMPLETED
    }

    private static final class Challenge {
        private String id;
        private String fromUsername;
        private String toUsername;
        private ChallengeStatus status;
        private long createdAt;
        private String battleId;
    }
}
