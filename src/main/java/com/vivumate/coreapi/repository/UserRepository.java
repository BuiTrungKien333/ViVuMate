package com.vivumate.coreapi.repository;

import com.vivumate.coreapi.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;


@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Modifying
    @Query(value = "UPDATE tbl_users SET deleted_at = NULL, status = 'ACTIVE' WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

//    @Query("SELECT u FROM User u WHERE LOWER(u.username) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
//           "OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
//           "OR LOWER(u.email) LIKE LOWER(CONCAT('%', :keyword, '%'))")
//    Page<User> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
