package com.vivumate.coreapi.mapper;

import com.vivumate.coreapi.dto.response.UserResponse;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

@Component
public class UserMapper {

    public static UserResponse toUserResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .gender(user.getGender())
                .dateOfBirth(user.getDateOfBirth())
                .bio(user.getBio())
                .avatarUrl(user.getAvatarUrl())
                .coverUrl(user.getCoverUrl())
                .roles(user.getRoles().stream()
                        .map(Role::getName)
                        .collect(Collectors.toSet()))
                .status(user.getStatus())
                .verified(user.isVerified())
                .provider(user.getProvider())
                .online(user.isOnline())
                .lastSeen(user.getLastSeen())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }

}

