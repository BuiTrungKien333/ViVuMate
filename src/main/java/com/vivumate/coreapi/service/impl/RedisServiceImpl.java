package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "REDIS_SERVICE")
public class RedisServiceImpl implements RedisService {

    private final StringRedisTemplate redisTemplate;

    private static final String RESET_PWD_PREFIX = "reset_pwd:";
    private static final String COOLDOWN_PREFIX = "cooldown_reset:";
    private static final String VERIFY_EMAIL_PREFIX = "verify_email:";
    private static final String LOGIN_OTP_PREFIX = "login_otp:";

    @Override
    public void saveResetToken(String email, String token, long ttl) {
        String key = RESET_PWD_PREFIX + email;
        redisTemplate.opsForValue().set(key, token, ttl, TimeUnit.MILLISECONDS);
        log.debug("Saved Reset Token for email: {} (Expiration: {} milliseconds)", email, ttl);
    }

    @Override
    public String getResetToken(String email) {
        String key = RESET_PWD_PREFIX + email;
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void deleteResetToken(String email) {
        String key = RESET_PWD_PREFIX + email;
        redisTemplate.delete(key);
        log.debug("Deleted the Reset Token for email: {}", email);
    }

    @Override
    public boolean isCooldown(String email) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + email));
    }

    @Override
    public void setCooldown(String email, long seconds) {
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + email, "locked", seconds, TimeUnit.SECONDS);
    }

    @Override
    public void saveVerifyToken(String email, String token, long ttl) {
        String key = VERIFY_EMAIL_PREFIX + email;
        redisTemplate.opsForValue().set(key, token, ttl, TimeUnit.MILLISECONDS);

        log.debug("Verify Token saved for email: {}", email);
    }

    @Override
    public String getVerifyToken(String email) {
        String key = VERIFY_EMAIL_PREFIX + email;
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void deleteVerifyToken(String email) {
        String key = VERIFY_EMAIL_PREFIX + email;
        redisTemplate.delete(key);
    }

    // --- Login OTP ---

    @Override
    public void saveLoginOtp(String email, String otp, long ttl) {
        String key = LOGIN_OTP_PREFIX + email;
        redisTemplate.opsForValue().set(key, otp, ttl, TimeUnit.MILLISECONDS);
        log.debug("Login OTP saved for email: {}", email);
    }

    @Override
    public String getLoginOtp(String email) {
        String key = LOGIN_OTP_PREFIX + email;
        return redisTemplate.opsForValue().get(key);
    }

    @Override
    public void deleteLoginOtp(String email) {
        String key = LOGIN_OTP_PREFIX + email;
        redisTemplate.delete(key);
    }

}
