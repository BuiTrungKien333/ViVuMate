package com.vivumate.coreapi.mapper;

import com.vivumate.coreapi.dto.response.UserResponse;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

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
                .status(user.getStatus())
                .verified(user.isVerified())
                .provider(user.getProvider())
                .online(user.isOnline())
                .lastSeen(user.getLastSeen())
                .lastLoginAt(user.getLastLoginAt())
                .createdAt(user.getCreatedAt())
                .build();
    }

    public static Set<GrantedAuthority> buildAuthorities(Set<Role> roles) {
        Set<GrantedAuthority> authorities = new HashSet<>();

        roles.forEach(role -> {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));
            role.getPermissions().forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(permission.getPermissionCode().name()))
            );
        });
        return authorities;
    }
}

