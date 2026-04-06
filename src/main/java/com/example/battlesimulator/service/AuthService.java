package com.example.battlesimulator.service;

import com.example.battlesimulator.dto.AuthRequest;
import com.example.battlesimulator.dto.AuthResponse;
import com.example.battlesimulator.model.UserAccount;
import com.example.battlesimulator.repository.UserAccountRepository;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthService {

    private static final long ONLINE_WINDOW_MS = 60_000L;

    private final UserAccountRepository userAccountRepository;
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    public AuthService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    public AuthResponse signup(AuthRequest request) {
        String username = normalizeUsername(request.username());
        String password = request.password() == null ? "" : request.password();
        if (username == null || username.length() < 3) {
            return new AuthResponse(false, "Username must be at least 3 characters.", null, null);
        }
        if (password.length() < 4) {
            return new AuthResponse(false, "Password must be at least 4 characters.", null, null);
        }
        if (userAccountRepository.existsByUsernameIgnoreCase(username)) {
            return new AuthResponse(false, "That username is already taken.", null, null);
        }

        UserAccount user = new UserAccount();
        user.setUsername(username);
        user.setPasswordHash(hash(password));
        userAccountRepository.save(user);
        return createSession(username, "Account created.");
    }

    public AuthResponse login(AuthRequest request) {
        String username = normalizeUsername(request.username());
        String password = request.password() == null ? "" : request.password();
        if (username == null || password.isBlank()) {
            return new AuthResponse(false, "Enter your username and password.", null, null);
        }

        return userAccountRepository.findByUsernameIgnoreCase(username)
                .filter(user -> user.getPasswordHash().equals(hash(password)))
                .map(user -> createSession(user.getUsername(), "Logged in."))
                .orElse(new AuthResponse(false, "Invalid username or password.", null, null));
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public String requireUsername(String token) {
        SessionInfo info = token == null ? null : sessions.get(token);
        if (info == null) {
            throw new IllegalArgumentException("Unauthorized");
        }
        info.touch();
        return info.username();
    }

    public String getUsernameIfPresent(String token) {
        SessionInfo info = token == null ? null : sessions.get(token);
        if (info == null) {
            return null;
        }
        info.touch();
        return info.username();
    }

    public boolean isOnline(String username) {
        long cutoff = System.currentTimeMillis() - ONLINE_WINDOW_MS;
        return sessions.values().stream()
                .anyMatch(info -> info.username().equalsIgnoreCase(username) && info.lastSeenAt() >= cutoff);
    }

    public java.util.List<String> getOnlineUsernames(String exceptUsername, String query) {
        long cutoff = System.currentTimeMillis() - ONLINE_WINDOW_MS;
        String normalizedQuery = query == null ? "" : query.trim().toLowerCase();
        return sessions.values().stream()
                .filter(info -> info.lastSeenAt() >= cutoff)
                .map(SessionInfo::username)
                .distinct()
                .filter(username -> exceptUsername == null || !username.equalsIgnoreCase(exceptUsername))
                .filter(username -> normalizedQuery.isBlank() || username.toLowerCase().contains(normalizedQuery))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private AuthResponse createSession(String username, String message) {
        String token = UUID.randomUUID().toString();
        sessions.put(token, new SessionInfo(username, System.currentTimeMillis()));
        return new AuthResponse(true, message, username, token);
    }

    private String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        String cleaned = username.trim();
        return cleaned.isBlank() ? null : cleaned;
    }

    private String hash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(bytes);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not hash password", ex);
        }
    }

    private static final class SessionInfo {
        private final String username;
        private volatile long lastSeenAt;

        private SessionInfo(String username, long lastSeenAt) {
            this.username = username;
            this.lastSeenAt = lastSeenAt;
        }

        private String username() {
            return username;
        }

        private long lastSeenAt() {
            return lastSeenAt;
        }

        private void touch() {
            this.lastSeenAt = System.currentTimeMillis();
        }
    }
}
