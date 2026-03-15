package com.vivumate.coreapi.service;

import com.vivumate.coreapi.dto.request.*;
import com.vivumate.coreapi.dto.response.AuthenticationResponse;

public interface AuthenticationService {
    AuthenticationResponse register(UserCreationRequest request);

    AuthenticationResponse authenticate(AuthenticationRequest request);

    AuthenticationResponse refreshToken(RefreshTokenRequest request);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    void logout(String accessToken, String refreshToken);
}
