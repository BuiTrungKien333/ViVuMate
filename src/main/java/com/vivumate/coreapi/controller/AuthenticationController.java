package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.dto.request.AuthenticationRequest;
import com.vivumate.coreapi.dto.request.RefreshTokenRequest;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.service.AuthenticationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Slf4j(topic = "AUTHENTICATION_CONTROLLER")
@Tag(name = "Authentication", description = "APIs related to authentication (Login, Refresh Token, Logout)")
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    @Operation(
            summary = "User Login",
            description = "Validates username and password, then returns an Access Token and Refresh Token. " +
                    "The Access Token is required to access protected endpoints."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Login successful")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "1004",
            description = "Invalid username or password",
            content = @Content
    )
    @PostMapping("/login")
    public ApiResponse<AuthenticationResponse> login(@RequestBody @Valid AuthenticationRequest request) {
        log.info("Login request received for username={}", request.getUsername());
        return ApiResponse.success(authenticationService.authenticate(request));
    }


    @Operation(
            summary = "Refresh Access Token",
            description = "When the Access Token expires, use the Refresh Token to obtain a new pair of tokens " +
                    "without requiring the user to log in again."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Token refreshed successfully")
    @PostMapping("/refresh-token")
    public ApiResponse<AuthenticationResponse> refreshToken(@RequestBody @Valid RefreshTokenRequest request) {
        log.info("Refresh token request received");
        return ApiResponse.success(authenticationService.refreshToken(request));
    }


    @Operation(
            summary = "User Logout",
            description = "Revokes the Refresh Token and adds the current Access Token to the blacklist, " +
                    "preventing further usage."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Logout successful")
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
