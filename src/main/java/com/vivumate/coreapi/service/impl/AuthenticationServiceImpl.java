package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.dto.request.*;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;
import com.vivumate.coreapi.entity.RefreshToken;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.enums.AuthProvider;
import com.vivumate.coreapi.enums.TokenType;
import com.vivumate.coreapi.enums.UserStatus;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.mapper.UserMapper;
import com.vivumate.coreapi.repository.RefreshTokenRepository;
import com.vivumate.coreapi.repository.RoleRepository;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.security.JwtUtils;
import com.vivumate.coreapi.service.AuthenticationService;
import com.vivumate.coreapi.service.EmailService;
import com.vivumate.coreapi.service.RedisService;
import com.vivumate.coreapi.service.TokenBlacklistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION_SERVICE")
public class AuthenticationServiceImpl implements AuthenticationService {

    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtUtils jwtUtils;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistService tokenBlacklistService;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final RedisService redisService;

    @Value("${vivumate.jwt.access.expiration}")
    private long accessExpiration;

    @Value("${vivumate.jwt.refresh.expiration}")
    private long refreshTokenExpiration;

    @Value("${vivumate.jwt.reset.expiration}")
    private long resetExpiration;

    @Value("${vivumate.app.frontend-url}")
    private String frontendUrl;

    // --- REGISTER ---
    @Override
    @Transactional
    public AuthenticationResponse register(UserCreationRequest request) {
        log.info("(Attempt) Register user: {}", request.getEmail());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }

        Role defaultRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        String baseUsername = request.getEmail().substring(0, request.getEmail().indexOf("@"));
        String username = baseUsername;
        while (userRepository.existsByUsername(username)) {
            int suffix = java.util.concurrent.ThreadLocalRandom.current().nextInt(10000);
            username = baseUsername + "_" + String.format("%04d", suffix);
        }

        User user = User.builder()
                .username(username)
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .gender(request.getGender())
                .dateOfBirth(request.getDateOfBirth())
                .status(UserStatus.ACTIVE)
                .roles(java.util.Set.of(defaultRole))
                .provider(AuthProvider.LOCAL)
                .verified(false)
                .online(true)
                .lastLoginAt(LocalDateTime.now())
                .build();

        User savedUser = userRepository.save(user);
        log.info("(Success) Register user: {} (username={})", savedUser.getEmail(), savedUser.getUsername());

        savedUser.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));

        String accessToken = jwtUtils.generateToken(savedUser);
        String refreshToken = jwtUtils.generateRefreshToken(savedUser);
        saveUserRefreshTokenToDb(savedUser, refreshToken);

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessExpiration)
                .userId(savedUser.getId())
                .username(savedUser.getUsername())
                .roles(savedUser.getRoles().stream().map(Role::getName).collect(Collectors.toSet()))
                .build();
    }

    // --- LOGIN ---
    @Override
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
    @Override
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

    // --- FORGOT PASSWORD ---
    @Override
    public void forgotPassword(ForgotPasswordRequest request) {
        log.info("(Attempt) Forgot password for email: {}", request.getEmail());

        if(redisService.isCooldown(request.getEmail())) {
            throw new AppException(ErrorCode.TOO_MANY_REQUESTS);
        }

        redisService.setCooldown(request.getEmail(), 60);

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            log.warn("Someone tried to recover their password using a non-existent email address: {}", request.getEmail());
            return;
        }

        User user = userOpt.get();
        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));

        String resetToken = jwtUtils.generateResetToken(user);
        redisService.saveResetToken(user.getUsername(), resetToken, resetExpiration);

        String resetLink = frontendUrl + "/reset-password?token=" + resetToken;
        emailService.sendResetPasswordEmail(user.getEmail(), user.getFullName(), resetLink);
        log.info("(Success) Reset password email queued for: {}", request.getEmail());
    }

    // --- RESET PASSWORD ---
    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        log.info("(Attempt) Reset password");
        String username = jwtUtils.extractUsername(request.getToken(), TokenType.RESET_TOKEN);
        String savedTokenUser = redisService.getResetToken(username);

        if(savedTokenUser == null || !savedTokenUser.equals(request.getToken())) {
            log.warn("(Failed) Reset token not found in Redis or mismatched for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        var userDetails = userDetailsService.loadUserByUsername(username);

        if (!jwtUtils.isTokenValid(request.getToken(), userDetails, TokenType.RESET_TOKEN)) {
            log.warn("(Failed) Reset token invalid or expired for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        User user = (User) userDetails;
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        redisService.deleteResetToken(username);

        // Bảo mật nâng cao: Revoke tất cả RefreshToken
        refreshTokenRepository.revokeAllByUser(user);

        log.info("(Success) Password reset for user: {}", username);
    }

    // --- LOG OUT ---
    @Override
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
