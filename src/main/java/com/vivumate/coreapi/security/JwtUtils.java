package com.vivumate.coreapi.security;

import com.vivumate.coreapi.enums.TokenType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

@Component
@Slf4j
public class JwtUtils {

    @Value("${vivumate.jwt.secret-key}")
    private String secretKey;

    @Value("${vivumate.jwt.refreshKey}")
    private String refreshKey;

    @Value("${vivumate.jwt.resetKey}")
    private String resetKey;

    @Value("${vivumate.jwt.access.expiration}")
    private long jwtExpiration;

    @Value("${vivumate.jwt.refresh.expiration}")
    private long jwtRefreshExpiration;

    @Value("${vivumate.jwt.reset.expiration}")
    private long jwtResetExpiration;

    // --- GENERATE TOKEN ---
    public String generateToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, TokenType.ACCESS_TOKEN, jwtExpiration);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, TokenType.REFRESH_TOKEN, jwtRefreshExpiration);
    }

    public String generateResetToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails, TokenType.RESET_TOKEN, jwtResetExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails, TokenType type, long expiration) {
        Instant now = Instant.now();
        return Jwts.builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expiration)))
                .signWith(getSignInKey(type), Jwts.SIG.HS256)
                .compact();
    }

    // --- VALIDATE TOKEN ---
    public boolean isTokenValid(String token, UserDetails userDetails, TokenType type) {
        try {
            final String username = extractUsername(token, type);
            return (username.equals(userDetails.getUsername())) && !isTokenExpired(token, type);
        } catch (JwtException | IllegalArgumentException e) {
            log.error("Invalid JWT Token (Type: {}): {}", type, e.getMessage());
            return false;
        }
    }

    // --- EXTRACT DATA ---
    public String extractUsername(String token, TokenType type) {
        return extractClaim(token, type, Claims::getSubject);
    }

    private boolean isTokenExpired(String token, TokenType type) {
        return extractClaim(token, type, Claims::getExpiration).before(new Date());
    }

    public <T> T extractClaim(String token, TokenType type, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token, type);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token, TokenType type) {
        return Jwts.parser()
                .verifyWith(getSignInKey(type))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // --- GET KEY HELPER ---
    private SecretKey getSignInKey(TokenType type) {
        String keyString = switch (type) {
            case ACCESS_TOKEN -> secretKey;
            case REFRESH_TOKEN -> refreshKey;
            case RESET_TOKEN -> resetKey;
        };

        byte[] keyBytes = Decoders.BASE64.decode(keyString);
        return Keys.hmacShaKeyFor(keyBytes);
    }

}
