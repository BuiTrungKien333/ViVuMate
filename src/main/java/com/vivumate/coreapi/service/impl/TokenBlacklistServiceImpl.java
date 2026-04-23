package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.entity.enums.TokenType;
import com.vivumate.coreapi.security.JwtUtils;
import com.vivumate.coreapi.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "TOKEN_BLACKLIST_SERVICE")
public class TokenBlacklistServiceImpl implements TokenBlacklistService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final JwtUtils jwtUtils;

    private static final String BLACKLIST_PREFIX = "BLACKLIST_TOKEN:";

    public void blacklistToken(String token) {
        Date expiryDate = jwtUtils.extractClaim(token, TokenType.ACCESS_TOKEN, io.jsonwebtoken.Claims::getExpiration);

        long ttlInMillis = expiryDate.getTime() - System.currentTimeMillis();

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
