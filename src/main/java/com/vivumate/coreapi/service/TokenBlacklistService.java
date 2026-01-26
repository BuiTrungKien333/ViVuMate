package com.vivumate.coreapi.service;

import com.vivumate.coreapi.enums.TokenType;
import com.vivumate.coreapi.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class TokenBlacklistService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtils jwtUtils;

    private static final String BLACKLIST_PREFIX = "BLACKLIST_TOKEN:";

    public void blacklistToken(String token) {
        // Lấy thời gian hết hạn của Token
        Date expiryDate = jwtUtils.extractClaim(token, TokenType.ACCESS_TOKEN, io.jsonwebtoken.Claims::getExpiration);

        // Tính thời gian còn lại (TTL) = Hết hạn - Hiện tại
        long ttlInMillis = expiryDate.getTime() - System.currentTimeMillis();

        // Nếu token còn sống thì mới lưu vào Redis
        if (ttlInMillis > 0) {
            redisTemplate.opsForValue().set(
                    BLACKLIST_PREFIX + token,
                    "LOGGED_OUT",
                    ttlInMillis,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public boolean isBlacklisted(String token) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
    }

}
