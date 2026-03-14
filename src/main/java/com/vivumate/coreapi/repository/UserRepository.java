package com.vivumate.coreapi.repository;

import com.vivumate.coreapi.dto.response.UserMiniResponse;
import com.vivumate.coreapi.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    Optional<User> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Modifying
    @Query(value = "UPDATE tbl_users SET deleted_at = NULL, status = 'ACTIVE' WHERE id = :id AND deleted_at IS NOT NULL", nativeQuery = true)
    int restoreById(@Param("id") Long id);

    @Query("select new com.vivumate.coreapi.dto.response.UserMiniResponse(u.id, u.username, u.email, u.fullName, u.avatarUrl, u.online, u.lastSeen) from User u where u.id in :ids")
    List<UserMiniResponse> findChatMembersByIds(@Param("ids") List<Long> ids);

    @Query("select new com.vivumate.coreapi.dto.response.UserMiniResponse(u.id, u.username, u.email, u.fullName, u.avatarUrl, u.online, u.lastSeen) from User u" +
            " where u.username like concat('%', :keyword, '%') or lower(u.fullName) like concat('%', :keyword, '%')")
    Page<UserMiniResponse> searchByKeyword(@Param("keyword") String keyword, Pageable pageable);
}
