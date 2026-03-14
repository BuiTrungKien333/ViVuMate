package com.vivumate.coreapi.service;

import com.vivumate.coreapi.dto.request.AuthenticationRequest;
import com.vivumate.coreapi.dto.request.RefreshTokenRequest;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;
import com.vivumate.coreapi.entity.RefreshToken;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.enums.TokenType;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.repository.RefreshTokenRepository;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION_SERVICE")
public class AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtUtils jwtUtils;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;

    @Value("${vivumate.jwt.access.expiration}")
    private long accessExpiration;

    @Value("${vivumate.jwt.refresh.expiration}")
    private long refreshTokenExpiration;

    // --- LOGIN ---
    @Transactional
    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        log.info("(Attempt) Login user: {}", request.getIdentifier());

        var authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getIdentifier(), request.getPassword()));

        var user = (User) authentication.getPrincipal();

        user.setLastLoginAt(LocalDateTime.now());
        user.setOnline(true);
        userRepository.save(user);

        String accessToken = jwtUtils.generateToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);

        saveUserRefreshTokenToDb(user, refreshToken);
        log.info("(Success) Login user: {}", request.getIdentifier());

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessExpiration)
                .userId(user.getId())
                .username(user.getUsername())
                .roles(user.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .build();
    }

    // --- REFRESH TOKEN ---
    public AuthenticationResponse refreshToken(RefreshTokenRequest request) {
        String refreshToken = request.getRefreshToken();
        log.info("(Attempt) Refresh Token");

        String username = jwtUtils.extractUsername(refreshToken, TokenType.REFRESH_TOKEN);

        var userDetails = userDetailsService.loadUserByUsername(username);

        if (!jwtUtils.isTokenValid(refreshToken, userDetails, TokenType.REFRESH_TOKEN)) {
            log.warn("(Failed Refresh token invalid or expired for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        var storedToken = refreshTokenRepository.findByToken(refreshToken)
                .orElseThrow(() -> new AppException(ErrorCode.TOKEN_INVALID));

        if (storedToken.isRevoked()) {
            log.warn("Security Alert: Attempt to use revoked token by user {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        String newAccessToken = jwtUtils.generateToken(userDetails);
        String newRefreshToken = jwtUtils.generateRefreshToken(userDetails);

        saveUserRefreshTokenToDb((User) userDetails, newRefreshToken);

        log.info("(Success) Refreshed token for user: {}", username);

        return AuthenticationResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .expiresIn(accessExpiration)
                .tokenType("Bearer")
                .username(username)
                .build();
    }

    // --- LOG OUT ---
    @Transactional
    public void logout(String accessToken, String refreshToken) {
        log.info("(Attempt) Logout");
        tokenBlacklistService.blacklistToken(accessToken.substring(7));

        var storedRefreshToken = refreshTokenRepository.findByToken(refreshToken)
                .orElse(null);

        if (storedRefreshToken != null) {
            storedRefreshToken.setRevoked(true);
            refreshTokenRepository.save(storedRefreshToken);

            User user = storedRefreshToken.getUser();
            user.setOnline(false);
            user.setLastSeen(LocalDateTime.now());
            userRepository.save(user);
        }
    }

    private void saveUserRefreshTokenToDb(User user, String jwtToken) {
        var token = RefreshToken.builder()
                .user(user)
                .token(jwtToken)
                .revoked(false)
                .expiryDate(java.time.Instant.now().plusMillis(refreshTokenExpiration))
                .build();

        refreshTokenRepository.save(token);
    }
}
