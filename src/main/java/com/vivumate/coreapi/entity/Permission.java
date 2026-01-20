package com.vivumate.coreapi.entity;

import com.vivumate.coreapi.enums.PermissionCode;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tbl_permissions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Permission extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private PermissionCode permissionCode;

    @Column(length = 500)
    private String description;
}
