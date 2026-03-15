package com.vivumate.coreapi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RefreshTokenRequest {

    @NotBlank(message = "Refresh Token must be not blank.")
    private String refreshToken;
}
