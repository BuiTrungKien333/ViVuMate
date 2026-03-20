package com.vivumate.coreapi.service;

public interface RedisService {

    void saveResetToken(String email, String token, long ttl);

    String getResetToken(String email);

    void deleteResetToken(String email);

    boolean isCooldown(String email);

    void setCooldown(String email, long seconds);

    void saveVerifyToken(String email, String token, long ttl);

    String getVerifyToken(String email);

    void deleteVerifyToken(String email);

}
