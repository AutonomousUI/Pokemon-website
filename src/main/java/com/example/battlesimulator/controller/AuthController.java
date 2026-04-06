package com.example.battlesimulator.controller;

import com.example.battlesimulator.dto.AuthRequest;
import com.example.battlesimulator.dto.AuthResponse;
import com.example.battlesimulator.service.AuthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
public class AuthController {

    public static final String AUTH_HEADER = "X-Auth-Token";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@RequestBody AuthRequest request) {
        AuthResponse response = authService.signup(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.badRequest().body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@RequestBody AuthRequest request) {
        AuthResponse response = authService.login(request);
        return response.success() ? ResponseEntity.ok(response) : ResponseEntity.status(401).body(response);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@RequestHeader(value = AUTH_HEADER, required = false) String token) {
        authService.logout(token);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/me")
    public ResponseEntity<AuthResponse> me(@RequestHeader(value = AUTH_HEADER, required = false) String token) {
        String username = authService.getUsernameIfPresent(token);
        if (username == null) {
            return ResponseEntity.status(401).body(new AuthResponse(false, "Unauthorized", null, null));
        }
        return ResponseEntity.ok(new AuthResponse(true, "Authenticated", username, token));
    }
}
