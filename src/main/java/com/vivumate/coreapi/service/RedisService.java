package com.vivumate.coreapi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "REDIS_SERVICE")
public class RedisService {

    private final StringRedisTemplate redisTemplate;

    private static final String RESET_PWD_PREFIX = "reset_pwd:";
    private static final String COOLDOWN_PREFIX = "cooldown_reset:";

    public void saveResetToken(String email, String token, long ttl) {
        String key = RESET_PWD_PREFIX + email;
        redisTemplate.opsForValue().set(key, token, ttl, TimeUnit.MILLISECONDS);
        log.debug("Saved Reset Token for email: {} (Expiration: {} milliseconds)", email, ttl);
    }

    public String getResetToken(String email) {
        String key = RESET_PWD_PREFIX + email;
        return redisTemplate.opsForValue().get(key);
    }

    public void deleteResetToken(String email) {
        String key = RESET_PWD_PREFIX + email;
        redisTemplate.delete(key);
        log.debug("Deleted the Reset Token for email: {}", email);
    }

    public boolean isCooldown(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + email));
    }

    public void setCooldown(String email, long seconds) {
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + email, "locked", seconds, TimeUnit.SECONDS);
    }

}
