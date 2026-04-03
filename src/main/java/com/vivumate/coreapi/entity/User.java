package com.vivumate.coreapi.entity;

import com.vivumate.coreapi.entity.enums.AuthProvider;
import com.vivumate.coreapi.entity.enums.Gender;
import com.vivumate.coreapi.entity.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = {"password", "roles"})
@Entity
@Table(name = "tbl_users")
@SQLDelete(sql = "update tbl_users set deleted_at = NOW() where id = ?")
@SQLRestriction("deleted_at is null")
public class User extends BaseEntity implements UserDetails {

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "password", length = 255)
    private String password;

    @Column(name = "full_name", nullable = false, length = 100)
    @Nationalized
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender")
    private Gender gender;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "bio", length = 500)
    @Nationalized
    private String bio;

    @Column(name = "avatar_url", length = 255)
    private String avatarUrl;

    @Column(name = "cover_url", length = 255)
    private String coverUrl;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "tbl_users_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private UserStatus status;

    @Column(name = "is_verified")
    @Builder.Default
    private boolean verified = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_id")
    private String providerId;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "is_online", nullable = false, columnDefinition = "boolean default false")
    private boolean online;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

    // === UserDetails implementation ===
    @Transient
    private Set<GrantedAuthority> authorities;

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return status != UserStatus.BANNED;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return deletedAt == null && status != UserStatus.INACTIVE;
    }

}
