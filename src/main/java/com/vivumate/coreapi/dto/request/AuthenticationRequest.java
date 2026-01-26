package com.vivumate.coreapi.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class AuthenticationRequest {

    @NotBlank
    private String username;

    @NotBlank
    private String password;
}
