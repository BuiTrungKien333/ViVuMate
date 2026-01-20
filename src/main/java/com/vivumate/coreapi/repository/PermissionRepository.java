package com.vivumate.coreapi.repository;

import com.vivumate.coreapi.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
}
