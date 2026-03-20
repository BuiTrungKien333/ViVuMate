package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.dto.request.*;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.service.AuthenticationService;
import com.vivumate.coreapi.utils.Translator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTHENTICATION_CONTROLLER")
@Tag(name = "Authentication", description = "APIs related to authentication (Register, Login, Refresh Token, Logout)")
public class AuthenticationController {

    private final AuthenticationService authenticationService;
    private final Translator translator;

    @Operation(summary = "User Login", description = "Validates username and password, then returns an Access Token and Refresh Token. "
            +
            "The Access Token is required to access protected endpoints.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1004", description = "Invalid username or password", content = @Content)
    @PostMapping("/login")
    public ApiResponse<AuthenticationResponse> login(@RequestBody @Valid AuthenticationRequest request) {
        log.info("Login request received for identifier={}", request.getIdentifier());
        return ApiResponse.success(authenticationService.authenticate(request));
    }

    @Operation(summary = "Register new account", description = "Creates a new user account and immediately returns a JWT token pair so the user is logged in right away.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Account created successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email already exists", content = @Content)
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<String> register(@RequestBody @Valid UserCreationRequest request) {
        log.info("Register request received for email={}", request.getEmail());
        return ApiResponse.success(authenticationService.register(request));
    }

    @Operation(summary = "Email verification and automatic login",
            description = "Verifies the user's email using a token sent during registration. "
                    + "If valid, activates the account and returns a JWT token pair for automatic login.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Email verified and login successful")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1007", description = "Invalid or expired verification token", content = @Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1020", description = "Account already verified", content = @Content)
    @PostMapping("/verify-email")
    public ApiResponse<AuthenticationResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        return ApiResponse.success(authenticationService.verifyEmail(request));
    }

    @Operation(summary = "Refresh Access Token", description = "When the Access Token expires, use the Refresh Token to obtain a new pair of tokens without requiring the user to log in again.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refreshed successfully")
    @PostMapping("/refresh-token")
    public ApiResponse<AuthenticationResponse> refreshToken(@RequestBody @Valid RefreshTokenRequest request) {
        log.info("Refresh token request received");
        return ApiResponse.success(authenticationService.refreshToken(request));
    }

    @Operation(summary = "User Logout", description = "Revokes the Refresh Token and adds the current Access Token to the blacklist, "
            +
            "preventing further usage.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logout successful")
    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request,
                                      @RequestBody RefreshTokenRequest refreshTokenRequest) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        authenticationService.logout(authHeader, refreshTokenRequest.getRefreshToken());
        return ApiResponse.success(translator.toLocale("success.auth.logout"));
    }

    @Operation(summary = "Forgot Password",
            description = "Sends a password reset email to the provided email address. "
                    + "For security, always returns the same response regardless of whether the email exists.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Reset email sent (if email exists)")
    @PostMapping("/forgot-password")
    public ApiResponse<String> forgotPassword(@RequestBody @Valid ForgotPasswordRequest request) {
        log.info("Forgot password request received for email={}", request.getEmail());
        authenticationService.forgotPassword(request);
        return ApiResponse.success(translator.toLocale("success.auth.forgot_password"));
    }

    @Operation(summary = "Reset Password",
            description = "Resets the user's password using a valid reset token. "
                    + "After resetting, all existing refresh tokens are revoked, forcing re-authentication on all devices.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password reset successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1007", description = "Invalid or expired reset token", content = @Content)
    @PostMapping("/reset-password")
    public ApiResponse<String> resetPassword(@RequestBody @Valid ResetPasswordRequest request) {
        log.info("Reset password request received");
        authenticationService.resetPassword(request);
        return ApiResponse.success(translator.toLocale("success.auth.reset_password"));
    }

    @Operation(summary = "Verify Login OTP",
            description = "Verifies the OTP sent to the user's email during a suspicious login attempt. "
                    + "If valid, issues JWT tokens and completes the login process.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "OTP verified, login successful")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1022", description = "Invalid OTP code", content = @Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "1023", description = "OTP has expired", content = @Content)
    @PostMapping("/verify-login-otp")
    public ApiResponse<AuthenticationResponse> verifyLoginOtp(@RequestBody @Valid VerifyLoginOtpRequest request) {
        log.info("Verify login OTP request received for email={}", request.getEmail());
        return ApiResponse.success(authenticationService.verifyLoginOtp(request));
    }

}
