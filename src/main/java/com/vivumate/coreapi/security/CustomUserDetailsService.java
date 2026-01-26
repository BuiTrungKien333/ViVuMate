package com.vivumate.coreapi.security;

import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.enums.UserStatus;
import com.vivumate.coreapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("USER_NOT_FOUND"));

        if (user.getDeletedAt() != null) {
            log.warn("Deleted user tried to login: {}", username);
            throw new DisabledException("ACCOUNT_DELETED");
        }

        if (user.getStatus() == UserStatus.BANNED) {
            log.warn("Banned user tried to login: {}", username);
            throw new LockedException("ACCOUNT_BANNED");
        }

        if (user.getStatus() == UserStatus.INACTIVE) {
            log.warn("Inactive user tried to login: {}", username);
            throw new DisabledException("ACCOUNT_INACTIVE");
        }

        Set<GrantedAuthority> authorities = new HashSet<>();

        user.getRoles().forEach(role -> {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + role.getName()));

            role.getPermissions().forEach(permission -> {
                authorities.add(new SimpleGrantedAuthority(permission.getPermissionCode().name()));
            });
        });

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getPassword(),
                authorities
        );
    }
}
