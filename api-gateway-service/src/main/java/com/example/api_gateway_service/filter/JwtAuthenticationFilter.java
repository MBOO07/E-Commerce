package com.example.api_gateway_service.filter;

import io.jsonwebtoken.Claims;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    @Autowired
    private JwtUtil jwtUtil;

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> PUBLIC_URLS = List.of(
            "/api/auth/**",
            "/eureka/**",
            "/api/payments/verify",
            "/payments/verify",
            "/actuator/**"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();
        HttpMethod method = request.getMethod();

        // 1. Allow bypass without authentication for /api/auth/** and /eureka/**
        for (String publicUrl : PUBLIC_URLS) {
            if (pathMatcher.match(publicUrl, path)) {
                return chain.filter(exchange);
            }
        }

        // 2. Allow bypass without authentication for GET /api/products/** (public product catalog)
        if (HttpMethod.GET.equals(method) && (pathMatcher.match("/api/products/**", path) || pathMatcher.match("/products/**", path))) {
            return chain.filter(exchange);
        }

        // 3. For secured routes, extract token from Authorization: Bearer <token>
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return onError(exchange, HttpStatus.UNAUTHORIZED);
        }

        String token = authHeader.substring(7);

        // 4. Validate JWT signature and expiration. If invalid or missing, terminate with HTTP 401 Unauthorized
        try {
            if (!jwtUtil.validateToken(token)) {
                return onError(exchange, HttpStatus.UNAUTHORIZED);
            }

            Claims claims = jwtUtil.extractAllClaims(token);
            String userId = jwtUtil.extractUserId(token);
            String role = jwtUtil.extractRole(token);
            String username = claims.getSubject();

            // 5. Mutate downstream request headers with X-User-Id and X-User-Role
            ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
            if (userId != null) {
                requestBuilder.header("X-User-Id", userId);
            }
            if (role != null) {
                requestBuilder.header("X-User-Role", role);
            }
            if (username != null) {
                requestBuilder.header("X-User-Name", username);
            }

            ServerHttpRequest mutatedRequest = requestBuilder.build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (Exception e) {
            return onError(exchange, HttpStatus.UNAUTHORIZED);
        }
    }

    private Mono<Void> onError(ServerWebExchange exchange, HttpStatus httpStatus) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(httpStatus);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -1;
    }
}
