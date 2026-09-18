package com.example.user_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String token;
    private String accessToken;
    private String refreshToken;
    private String type = "Bearer";

    public AuthResponse(String token) {
        this.token = token;
        this.accessToken = token;
        this.type = "Bearer";
    }

    public AuthResponse(String accessToken, String refreshToken) {
        this.token = accessToken;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.type = "Bearer";
    }
}
