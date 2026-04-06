package com.example.battlesimulator.controller;

import com.example.battlesimulator.dto.ChallengeRequest;
import com.example.battlesimulator.dto.LobbyStateResponse;
import com.example.battlesimulator.service.AuthService;
import com.example.battlesimulator.service.PvpLobbyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/lobby")
@CrossOrigin(origins = "*")
public class LobbyController {

    private final AuthService authService;
    private final PvpLobbyService pvpLobbyService;

    public LobbyController(AuthService authService, PvpLobbyService pvpLobbyService) {
        this.authService = authService;
        this.pvpLobbyService = pvpLobbyService;
    }

    @GetMapping("/state")
    public ResponseEntity<?> getState(@RequestHeader(AuthController.AUTH_HEADER) String token,
                                      @RequestParam(required = false) String query) {
        try {
            String username = authService.requireUsername(token);
            LobbyStateResponse response = pvpLobbyService.getLobbyState(username, query);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@RequestHeader(AuthController.AUTH_HEADER) String token,
                                       @RequestBody ChallengeRequest request) {
        try {
            String username = authService.requireUsername(token);
            pvpLobbyService.sendChallenge(username, request.targetUsername());
            return ResponseEntity.ok(Map.of("message", "Challenge sent."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/challenge/{challengeId}/accept")
    public ResponseEntity<?> accept(@RequestHeader(AuthController.AUTH_HEADER) String token,
                                    @PathVariable String challengeId) {
        try {
            String username = authService.requireUsername(token);
            pvpLobbyService.acceptChallenge(username, challengeId);
            return ResponseEntity.ok(Map.of("message", "Challenge accepted."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/challenge/{challengeId}/reject")
    public ResponseEntity<?> reject(@RequestHeader(AuthController.AUTH_HEADER) String token,
                                    @PathVariable String challengeId) {
        try {
            String username = authService.requireUsername(token);
            pvpLobbyService.rejectChallenge(username, challengeId);
            return ResponseEntity.ok(Map.of("message", "Challenge rejected."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }

    @PostMapping("/match/leave")
    public ResponseEntity<?> leave(@RequestHeader(AuthController.AUTH_HEADER) String token) {
        try {
            String username = authService.requireUsername(token);
            pvpLobbyService.leaveActiveMatch(username);
            return ResponseEntity.ok(Map.of("message", "Returned to lobby."));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
        }
    }
}
