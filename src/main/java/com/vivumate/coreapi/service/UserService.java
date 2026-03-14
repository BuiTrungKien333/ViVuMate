package com.vivumate.coreapi.service;

import com.vivumate.coreapi.dto.request.ChangePasswordRequest;
import com.vivumate.coreapi.dto.request.UserCreationRequest;
import com.vivumate.coreapi.dto.request.UserUpdateRequest;
import com.vivumate.coreapi.dto.response.PageResponse;
import com.vivumate.coreapi.dto.response.UserResponse;

import java.util.List;

public interface UserService {

    // User (self)
    UserResponse createUser(UserCreationRequest request);

    UserResponse getMyProfile();

    UserResponse updateMyProfile(UserUpdateRequest request);

    void changePassword(ChangePasswordRequest request);

    UserResponse updateAvatar(String avatarUrl);

    UserResponse updateCover(String coverUrl);

    // User (See other people)
    UserResponse getUserById(Long id);

    UserResponse getUserByUsername(String username);

    PageResponse<UserResponse> searchUsers(String keyword, int page, int size);

    List<UserResponse> getUsersByIds(List<Long> ids);

    void updateOnlineStatus(Long userId, boolean online);

    // Admin
    PageResponse<UserResponse> getAllUsers(int page, int size);

    UserResponse updateUser(Long id, UserUpdateRequest request);

    void deleteUser(Long id);

    void restoreUser(Long id);

    void toggleUserStatus(Long id);
}
