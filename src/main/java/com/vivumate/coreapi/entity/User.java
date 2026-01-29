package com.vivumate.coreapi.entity;

import com.vivumate.coreapi.enums.AuthProvider;
import com.vivumate.coreapi.enums.Gender;
import com.vivumate.coreapi.enums.UserStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Entity
@Table(name = "tbl_users")
@SQLDelete(sql = "update tbl_users set deleted_at = NOW() where id = ?")
@SQLRestriction("deleted_at is null")
public class User extends BaseEntity {

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(length = 255)
    private String password;

    @Column(unique = true, length = 15)
    private String phone;

    @Column(nullable = false, length = 100)
    @Nationalized
    private String fullName;

    @Enumerated(EnumType.STRING)
    private Gender gender;

    private LocalDate dateOfBirth;

    @Column(length = 500)
    @Nationalized
    private String bio;

    @Column(length = 255)
    private String avatarUrl;

    @Column(length = 255)
    private String coverUrl;

    @Column(length = 100)
    @Nationalized
    private String city;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "tbl_users_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    @Column(name = "is_verified")
    private boolean isVerified = false;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuthProvider provider;

    @Column(name = "provider_id")
    private String providerId;

}
