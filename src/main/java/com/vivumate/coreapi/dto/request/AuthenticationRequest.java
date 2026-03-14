package com.vivumate.coreapi.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class AuthenticationRequest {

    @Schema(description = "Username or Email", example = "admin or admin@gmail.com")
    @NotBlank(message = "Username or Email cannot be blank")
    private String identifier;

    @Schema(description = "Password", example = "admin123")
    @NotBlank(message = "Password cannot be blank")
    private String password;
}
