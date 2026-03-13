package com.vivumate.coreapi.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.vivumate.coreapi.enums.AuthProvider;
import com.vivumate.coreapi.enums.Gender;
import com.vivumate.coreapi.enums.UserStatus;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserResponse {

    private Long id;

    private String username;

    private String email;

    @JsonProperty("full_name")
    private String fullName;

    private Gender gender;

    @JsonProperty("date_of_birth")
    private LocalDate dateOfBirth;

    private String bio;

    @JsonProperty("avatar_url")
    private String avatarUrl;

    @JsonProperty("cover_url")
    private String coverUrl;

    private Set<String> roles;

    private UserStatus status;

    @JsonProperty("is_verified")
    private Boolean verified;

    private AuthProvider provider;

    private Boolean online;

    @JsonProperty("last_seen")
    private LocalDateTime lastSeen;

    @JsonProperty("last_login_at")
    private LocalDateTime lastLoginAt;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
