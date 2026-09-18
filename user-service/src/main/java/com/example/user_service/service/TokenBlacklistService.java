package com.example.user_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TokenBlacklistService {

    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String REFRESH_PREFIX = "refresh:";

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public void blacklistToken(String token, long remainingMs) {
        if (redisTemplate != null && remainingMs > 0) {
            try {
                redisTemplate.opsForValue().set(BLACKLIST_PREFIX + token, "true", remainingMs, TimeUnit.MILLISECONDS);
                log.info("Token blacklisted in Redis for {} ms", remainingMs);
            } catch (Exception e) {
                log.warn("Redis unavailable for token blacklisting: {}", e.getMessage());
            }
        }
    }

    public boolean isBlacklisted(String token) {
        if (redisTemplate != null) {
            try {
                return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
            } catch (Exception e) {
                log.warn("Redis unavailable during blacklist lookup: {}", e.getMessage());
                return false;
            }
        }
        return false;
    }

    public void storeRefreshToken(String username, String refreshToken, long durationMs) {
        if (redisTemplate != null && durationMs > 0) {
            try {
                redisTemplate.opsForValue().set(REFRESH_PREFIX + username, refreshToken, durationMs, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                log.warn("Redis unavailable for storing refresh token: {}", e.getMessage());
            }
        }
    }

    public String getRefreshToken(String username) {
        if (redisTemplate != null) {
            try {
                return redisTemplate.opsForValue().get(REFRESH_PREFIX + username);
            } catch (Exception e) {
                log.warn("Redis unavailable for retrieving refresh token: {}", e.getMessage());
            }
        }
        return null;
    }

    public void deleteRefreshToken(String username) {
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(REFRESH_PREFIX + username);
            } catch (Exception e) {
                log.warn("Redis unavailable for deleting refresh token: {}", e.getMessage());
            }
        }
    }
}
