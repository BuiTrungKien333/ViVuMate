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
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

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
    private long resetTokenExpiration;

    @Value("${vivumate.jwt.verify.expiration}")
    private long verifyTokenExpiration;

    @Value("${vivumate.jwt.otp-login.expiration}")
    private long otpExpiration;

    @Value("${vivumate.app.frontend-url}")
    private String frontendUrl;

    private static final int SUSPICIOUS_LOGIN_DAYS = 30;

    // --- REGISTER ---
    @Override
    @Transactional
    public String register(UserCreationRequest request) {
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
                .roles(java.util.Set.of(defaultRole))
                .provider(AuthProvider.LOCAL)
                .verified(false)
                .status(UserStatus.INACTIVE)
                .build();

        User savedUser = userRepository.save(user);
        log.info("(Success, Unverified) Register user: {} (username={})", savedUser.getEmail(),
                savedUser.getUsername());

        // Set authorities before generate token
        savedUser.setAuthorities(UserMapper.buildAuthorities(savedUser.getRoles()));

        // verify email
        String verifyToken = jwtUtils.generateVerifyToken(savedUser);

        redisService.saveVerifyToken(savedUser.getUsername(), verifyToken, verifyTokenExpiration);

        String verifyLink = frontendUrl + "/verify-email?token=" + verifyToken;
        emailService.sendVerificationEmail(savedUser.getEmail(), savedUser.getFullName(), verifyLink);

        return "Registration successful! Please check your email to activate your account.";
    }

    // --- LOGIN ---
    @Override
    @Transactional
    public AuthenticationResponse authenticate(AuthenticationRequest request) {
        log.info("(Attempt) Login user: {}", request.getIdentifier());

        var authentication = tryAuthenticate(request);

        var user = (User) authentication.getPrincipal();

        // Suspicious login detection: inactive > 30 days
        if (isSuspiciousLogin(user)) {
            log.warn("Suspicious login detected for user: {} - requiring OTP", user.getUsername());
            final String otp = generateOtp();
            redisService.saveLoginOtp(user.getEmail(), otp, otpExpiration);
            emailService.sendLoginOtpEmail(user.getEmail(), user.getFullName(), otp);

            return AuthenticationResponse.builder()
                    .requireOtp(true)
                    .build();
        }

        user.setLastLoginAt(LocalDateTime.now());
        user.setLastSeen(LocalDateTime.now());
        user.setOnline(true);
        userRepository.save(user);

        final String accessToken = jwtUtils.generateToken(user);
        final String refreshToken = jwtUtils.generateRefreshToken(user);

        saveUserRefreshTokenToDb(user, refreshToken);
        log.info("(Success) Login user: {}", request.getIdentifier());

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessExpiration)
                .userId(user.getId())
                .username(user.getUsername())
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

        if (redisService.isCooldown(request.getEmail())) {
            throw new AppException(ErrorCode.TOO_MANY_REQUESTS);
        }

        // chặn trước khi nó xuống được db để check email, chống spam
        redisService.setCooldown(request.getEmail(), 60);

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            log.debug("Someone tried to recover their password using a non-existent email address: {}",
                    request.getEmail());
            return;
        }

        User user = userOpt.get();
        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));

        String resetToken = jwtUtils.generateResetToken(user);
        redisService.saveResetToken(user.getUsername(), resetToken, resetTokenExpiration);

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

        if (savedTokenUser == null || !savedTokenUser.equals(request.getToken())) {
            log.warn("(Failed) Reset token not found in Redis or mismatched for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (!jwtUtils.isTokenValid(request.getToken(), user, TokenType.RESET_TOKEN)) {
            log.warn("(Failed) Reset token invalid or expired for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setLastSeen(LocalDateTime.now()); // Prevent suspicious login OTP after reset
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

    @Override
    @Transactional
    public AuthenticationResponse verifyEmail(VerifyEmailRequest request) {
        log.info("(Attempt) Verify email");

        String username = jwtUtils.extractUsername(request.getToken(), TokenType.VERIFY_TOKEN);
        String savedToken = redisService.getVerifyToken(username);

        // Check token in redis
        if (savedToken == null || !savedToken.equals(request.getToken())) {
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        // Validate JWT signature & expiry
        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));
        if (!jwtUtils.isTokenValid(request.getToken(), user, TokenType.VERIFY_TOKEN)) {
            log.warn("(Failed) Verify token invalid or expired for user: {}", username);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        if (user.isVerified()) {
            throw new AppException(ErrorCode.USER_ALREADY_VERIFIED);
        }

        user.setVerified(true);
        user.setStatus(UserStatus.ACTIVE);
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastSeen(LocalDateTime.now());
        user.setOnline(true);

        userRepository.save(user);

        redisService.deleteVerifyToken(username);

        final String accessToken = jwtUtils.generateToken(user);
        final String refreshToken = jwtUtils.generateRefreshToken(user);
        saveUserRefreshTokenToDb(user, refreshToken);

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessExpiration)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    // --- VERIFY LOGIN OTP ---
    @Override
    @Transactional
    public AuthenticationResponse verifyLoginOtp(VerifyLoginOtpRequest request) {
        log.info("(Attempt) Verify login OTP for email: {}", request.getEmail());

        String savedOtp = redisService.getLoginOtp(request.getEmail());

        if (savedOtp == null) {
            log.warn("(Failed) OTP expired or not found for email: {}", request.getEmail());
            throw new AppException(ErrorCode.OTP_EXPIRED);
        }

        if (!savedOtp.equals(request.getOtp())) {
            log.warn("(Failed) Invalid OTP for email: {}", request.getEmail());
            throw new AppException(ErrorCode.INVALID_OTP);
        }

        redisService.deleteLoginOtp(request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastSeen(LocalDateTime.now());
        user.setOnline(true);
        userRepository.save(user);

        String accessToken = jwtUtils.generateToken(user);
        String refreshToken = jwtUtils.generateRefreshToken(user);
        saveUserRefreshTokenToDb(user, refreshToken);

        log.info("(Success) OTP verified, login complete for user: {}", user.getUsername());

        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(accessExpiration)
                .userId(user.getId())
                .username(user.getUsername())
                .build();
    }

    // --- RESEND VERIFICATION EMAIL ---
    @Override
    public void resendVerification(ResendVerificationRequest request) {
        log.info("(Attempt) Resend verification email for: {}", request.getEmail());

        if (redisService.isCooldown(request.getEmail())) {
            throw new AppException(ErrorCode.TOO_MANY_REQUESTS);
        }

        redisService.setCooldown(request.getEmail(), 60);

        Optional<User> userOpt = userRepository.findByEmail(request.getEmail());
        if (userOpt.isEmpty()) {
            log.debug("Resend verification requested for non-existent email: {}", request.getEmail());
            return;
        }

        User user = userOpt.get();
        if (user.isVerified()) {
            throw new AppException(ErrorCode.USER_ALREADY_VERIFIED);
        }

        if (user.getDeletedAt() != null) {
            throw new AppException(ErrorCode.ACCOUNT_DELETED);
        }

        // Invalidate old token & generate new one
        redisService.deleteVerifyToken(user.getUsername());

        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));
        final String verifyToken = jwtUtils.generateVerifyToken(user);
        redisService.saveVerifyToken(user.getUsername(), verifyToken, verifyTokenExpiration);

        String verifyLink = frontendUrl + "/verify-email?token=" + verifyToken;
        emailService.sendVerificationEmail(user.getEmail(), user.getFullName(), verifyLink);

        log.info("(Success) Resend verification email queued for: {}", request.getEmail());
    }

    // --- PRIVATE HELPERS ---

    private Authentication tryAuthenticate(AuthenticationRequest request) {
        try {
            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            request.getIdentifier(), request.getPassword()));
        } catch (InternalAuthenticationServiceException e) {
            // DaoAuthenticationProvider wraps exceptions from loadUserByUsername()
            Throwable cause = e.getCause();
            if (cause instanceof DisabledException || cause instanceof LockedException) {
                throw mapAccountStatusException(cause);
            }
            throw e;
        } catch (DisabledException | LockedException e) {
            throw mapAccountStatusException(e);
        }
    }

    private AppException mapAccountStatusException(Throwable e) {
        if (e instanceof LockedException) {
            return new AppException(ErrorCode.ACCOUNT_LOCKED);
        }
        ErrorCode errorCode = switch (e.getMessage()) {
            case "ACCOUNT_DELETED" -> ErrorCode.ACCOUNT_DELETED;
            case "ACCOUNT_UNVERIFIED" -> ErrorCode.ACCOUNT_UNVERIFIED;
            default -> ErrorCode.ACCOUNT_DISABLED;
        };
        return new AppException(errorCode);
    }

    private boolean isSuspiciousLogin(User user) {
        if (user.getLastSeen() == null) {
            return true; // First login or never seen before
        }
        return user.getLastSeen().isBefore(LocalDateTime.now().minusDays(SUSPICIOUS_LOGIN_DAYS));
    }

    private String generateOtp() {
        SecureRandom random = new SecureRandom();
        int otp = random.nextInt(900000) + 100000; // 100000 - 999999
        return String.valueOf(otp);
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
