package com.example.user_service.controller;

import com.example.user_service.dto.*;
import com.example.user_service.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping({"/api/auth", ""})
public class AuthController {

    @Autowired
    private UserService userService;

    @PostMapping({"/register", "/api/auth/register"})
    public ResponseEntity<AuthResponse> register(@RequestBody RegisterRequest request) {
        AuthResponse response = userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping({"/login", "/api/auth/login"})
    public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {
        AuthResponse response = userService.login(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/refresh", "/api/auth/refresh"})
    public ResponseEntity<AuthResponse> refresh(@RequestBody RefreshTokenRequest request) {
        AuthResponse response = userService.refreshToken(request.getRefreshToken());
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/logout", "/api/auth/logout"})
    public ResponseEntity<Map<String, String>> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authHeader) {
        userService.logout(authHeader);
        return ResponseEntity.ok(Map.of("message", "Successfully logged out and token invalidated"));
    }

    @GetMapping({"/validate", "/api/auth/validate"})
    public ResponseEntity<TokenValidationResponse> validate(
            @RequestParam(required = false) String token,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authHeader) {

        String tokenToValidate = token;
        if (tokenToValidate == null && authHeader != null && authHeader.startsWith("Bearer ")) {
            tokenToValidate = authHeader.substring(7);
        }

        if (tokenToValidate == null) {
            return ResponseEntity.badRequest().body(TokenValidationResponse.builder()
                    .valid(false)
                    .message("Token is missing")
                    .build());
        }

        TokenValidationResponse response = userService.validateToken(tokenToValidate);
        return ResponseEntity.ok(response);
    }
}
