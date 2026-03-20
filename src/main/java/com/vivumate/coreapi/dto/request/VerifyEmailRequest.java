package com.vivumate.coreapi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class VerifyEmailRequest {

    @NotBlank(message = "Token cannot be empty.")
    private String token;

}
