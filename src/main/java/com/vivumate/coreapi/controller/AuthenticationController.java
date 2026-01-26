package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.dto.request.AuthenticationRequest;
import com.vivumate.coreapi.dto.request.RefreshTokenRequest;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    @Operation(summary = "Access token", description = "Get access_token by username and password")
    @PostMapping("/login")
    public ApiResponse<AuthenticationResponse> login(@RequestBody @Valid AuthenticationRequest request) {
        log.info("Login request received for username={}", request.getUsername());
        return ApiResponse.success(authenticationService.authenticate(request));
    }

    @Operation(summary = "Refresh token", description = "Get new access_token by refresh_token")
    @PostMapping("/refresh-token")
    public ApiResponse<AuthenticationResponse> refreshToken(@RequestBody @Valid RefreshTokenRequest request) {
        log.info("Refresh token request received");
        return ApiResponse.success(authenticationService.refreshToken(request));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout(HttpServletRequest request, @RequestBody RefreshTokenRequest refreshTokenRequest) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        authenticationService.logout(authHeader, refreshTokenRequest.getRefreshToken());
        return ApiResponse.success("Logout successfully!");
    }

}
