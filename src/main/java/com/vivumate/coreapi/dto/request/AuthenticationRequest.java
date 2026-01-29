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

    @Schema(description = "Username", example = "admin")
    @NotBlank(message = "Username cannot be blank")
    private String username;

    @Schema(description = "Password", example = "admin123")
    @NotBlank(message = "Password cannot be blank")
    private String password;
}
