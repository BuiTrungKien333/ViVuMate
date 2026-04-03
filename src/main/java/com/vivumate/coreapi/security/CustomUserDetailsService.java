package com.vivumate.coreapi.security;

import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.entity.enums.UserStatus;
import com.vivumate.coreapi.mapper.UserMapper;
import com.vivumate.coreapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String identifier) {
        User user = userRepository.findByUsernameOrEmail(identifier, identifier)
                .orElseThrow(() -> new UsernameNotFoundException("USER_NOT_FOUND"));

        String username = user.getUsername();
        if(!user.isVerified()) {
            log.warn("Unverified user tried to login: {}", username);
            throw new DisabledException("ACCOUNT_UNVERIFIED");
        }

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

        user.setAuthorities(UserMapper.buildAuthorities(user.getRoles()));
        return user;
    }
}
