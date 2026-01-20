package com.vivumate.coreapi.config;

import com.vivumate.coreapi.entity.Permission;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.enums.AuthProvider;
import com.vivumate.coreapi.enums.PermissionCode;
import com.vivumate.coreapi.enums.UserStatus;
import com.vivumate.coreapi.repository.PermissionRepository;
import com.vivumate.coreapi.repository.RoleRepository;
import com.vivumate.coreapi.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {

        if (roleRepository.count() > 0) {
            log.info("Data seeding skipped: Database already initialized.");
            return;
        }

        log.info("Starting Data Seeding...");

        Map<PermissionCode, Permission> permissionsMap = new HashMap<>();

        Map<PermissionCode, String> descriptions = Map.of(
                PermissionCode.USER_READ, "Allow viewing user profiles",
                PermissionCode.USER_UPDATE, "Allow updating own profile",
                PermissionCode.USER_MANAGE, "Allow managing users (Ban/Unban)",
                PermissionCode.POST_CREATE, "Allow creating travel posts",
                PermissionCode.POST_UPDATE, "Allow editing own posts",
                PermissionCode.POST_DELETE, "Allow deleting own posts",
                PermissionCode.POST_MANAGE, "Allow managing all posts (Content Moderation)",
                PermissionCode.LOCATION_VIEW, "Allow viewing locations details",
                PermissionCode.LOCATION_CREATE, "Allow creating new locations",
                PermissionCode.LOCATION_MANAGE, "Allow managing locations system-wide"
        );

        for (PermissionCode code : PermissionCode.values()) {
            Permission permission = Permission.builder()
                    .permissionCode(code)
                    .description(descriptions.getOrDefault(code, "No description"))
                    .build();
            permissionsMap.put(code, permissionRepository.save(permission));
        }

        Role adminRole = Role.builder()
                .name("ADMIN")
                .description("System Administrator with full access")
                .permissions(new HashSet<>(permissionsMap.values()))
                .build();

        Set<Permission> userPermissions = Set.of(
                permissionsMap.get(PermissionCode.USER_READ),
                permissionsMap.get(PermissionCode.USER_UPDATE),
                permissionsMap.get(PermissionCode.POST_CREATE),
                permissionsMap.get(PermissionCode.POST_UPDATE),
                permissionsMap.get(PermissionCode.POST_DELETE),
                permissionsMap.get(PermissionCode.LOCATION_VIEW)
        );

        Role userRole = Role.builder()
                .name("USER")
                .description("Standard User")
                .permissions(new HashSet<>(userPermissions))
                .build();

        roleRepository.saveAll(List.of(adminRole, userRole));

        // Admin
        User admin = User.builder()
                .username("admin")
                .email("admin@vivumate.com")
                .password(passwordEncoder.encode("admin123"))
                .fullName("System Admin")
                .roles(new HashSet<>(Set.of(adminRole)))
                .status(UserStatus.ACTIVE)
                .provider(AuthProvider.LOCAL)
                .isVerified(true)
                .build();

        // Standard User
        User user = User.builder()
                .username("vivumater")
                .email("user@vivumate.com")
                .password(passwordEncoder.encode("user123")) // Pass: user123
                .fullName("Bùi Trung Kiên")
                .roles(new HashSet<>(Set.of(userRole)))
                .status(UserStatus.ACTIVE)
                .provider(AuthProvider.LOCAL)
                .isVerified(false)
                .build();

        userRepository.saveAll(List.of(admin, user));

        log.info("----- DATA SEEDING COMPLETED SUCCESSFULLY -----");
        log.info("Admin Account: admin / admin123");
        log.info("User Account: vivumater / user123");
    }
}