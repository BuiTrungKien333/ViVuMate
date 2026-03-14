package com.vivumate.coreapi.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChangePasswordRequest {

    @NotBlank(message = "old password required")
    private String oldPassword;

    @NotBlank(message = "password require")
    @Size(min = 8, max = 128, message = "Password must be between 8 and 128 characters.")
    private String newPassword;

    @NotBlank(message = "confirm password require")
    private String confirmPassword;
}