package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.dto.request.ChangePasswordRequest;
import com.vivumate.coreapi.dto.request.UserCreationRequest;
import com.vivumate.coreapi.dto.request.UserUpdateRequest;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.PageResponse;
import com.vivumate.coreapi.dto.response.UserResponse;
import com.vivumate.coreapi.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Slf4j(topic = "USER_CONTROLLER")
@Tag(name = "User Management", description = "User information management")
public class UserController {

    private final UserService userService;

    // ==================== USER (SELF) ====================

    @Operation(summary = "Get my profile", description = "Returns the profile information of the currently authenticated user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile retrieved successfully")
    @GetMapping("/me")
    public ApiResponse<UserResponse> getMyProfile() {
        log.info("Get my profile request");
        return ApiResponse.success(userService.getMyProfile());
    }

    @Operation(summary = "Update my profile", description = "Updates the profile information of the currently authenticated user. Only non-null fields will be updated.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Profile updated successfully")
    @PutMapping("/me")
    public ApiResponse<UserResponse> updateMyProfile(@RequestBody @Valid UserUpdateRequest request) {
        log.info("Update my profile request");
        return ApiResponse.success(userService.updateMyProfile(request));
    }

    @Operation(summary = "Change password", description = "Changes the password of the currently authenticated user. Requires old password verification.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Password changed successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Old password incorrect or passwords do not match", content = @Content)
    @PatchMapping("/me/password")
    public ApiResponse<String> changePassword(@RequestBody @Valid ChangePasswordRequest request) {
        log.info("Change password request");
        userService.changePassword(request);
        return ApiResponse.success("Password changed successfully");
    }

    @Operation(summary = "Update avatar", description = "Updates the avatar URL of the currently authenticated user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Avatar updated successfully")
    @PatchMapping("/me/avatar")
    public ApiResponse<UserResponse> updateAvatar(@RequestBody Map<String, String> body) {
        log.info("Update avatar request");
        return ApiResponse.success(userService.updateAvatar(body.get("avatarUrl")));
    }

    @Operation(summary = "Update cover photo", description = "Updates the cover photo URL of the currently authenticated user.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Cover photo updated successfully")
    @PatchMapping("/me/cover")
    public ApiResponse<UserResponse> updateCover(@RequestBody Map<String, String> body) {
        log.info("Update cover photo request");
        return ApiResponse.success(userService.updateCover(body.get("coverUrl")));
    }

    // ==================== USER (SEE OTHERS) ====================

    @Operation(summary = "Get user by ID", description = "Returns the profile information of a specific user by their ID.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getUserById(
            @Parameter(description = "User ID") @PathVariable Long id) {
        log.info("Get user by ID: {}", id);
        return ApiResponse.success(userService.getUserById(id));
    }

    @Operation(summary = "Get user by username", description = "Returns the profile information of a specific user by their username.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @GetMapping("/username/{username}")
    public ApiResponse<UserResponse> getUserByUsername(
            @Parameter(description = "Username") @PathVariable String username) {
        log.info("Get user by username: {}", username);
        return ApiResponse.success(userService.getUserByUsername(username));
    }

    @Operation(summary = "Search users", description = "Searches users by keyword (username, full name, or email) with pagination.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Search results returned")
    @GetMapping("/search")
    public ApiResponse<PageResponse<UserResponse>> searchUsers(
            @Parameter(description = "Search keyword") @RequestParam String keyword,
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") @Min(1) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "10") @Min(1) int size) {
        log.info("Search users with keyword: {}", keyword);
        return ApiResponse.success(userService.searchUsers(keyword, page, size));
    }

    @Operation(summary = "Get users by IDs (batch)", description = "Returns a list of users matching the provided IDs. Useful for batch lookups.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Users returned")
    @PostMapping("/batch")
    public ApiResponse<List<UserResponse>> getUsersByIds(@RequestBody List<Long> ids) {
        log.info("Get users by IDs, count: {}", ids.size());
        return ApiResponse.success(userService.getUsersByIds(ids));
    }

    // ==================== ADMIN ====================

    @Operation(summary = "Get all users (Admin)", description = "Returns a paginated list of all users. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Users retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Access denied", content = @Content)
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<UserResponse>> getAllUsers(
            @Parameter(description = "Page number (1-based)") @RequestParam(defaultValue = "1") @Min(1) int page,
            @Parameter(description = "Page size") @RequestParam(defaultValue = "10") @Min(1) int size) {
        log.info("Admin: Get all users, page={}, size={}", page, size);
        return ApiResponse.success(userService.getAllUsers(page, size));
    }

    @Operation(summary = "Create user (Admin)", description = "Creates a new user account. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "User created successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Email already exists", content = @Content)
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserResponse> createUser(@RequestBody @Valid UserCreationRequest request) {
        log.info("Create user with email={}", request.getEmail());
        return ApiResponse.success(userService.createUser(request));
    }

    @Operation(summary = "Update user (Admin)", description = "Updates a user's profile information by ID. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User updated successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<UserResponse> updateUser(
            @Parameter(description = "User ID") @PathVariable Long id,
            @RequestBody @Valid UserUpdateRequest request) {
        log.info("Admin: Update user id={}", id);
        return ApiResponse.success(userService.updateUser(id, request));
    }

    @Operation(summary = "Delete user (Admin)", description = "Soft deletes a user by ID. The user can be restored later. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User deleted successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> deleteUser(
            @Parameter(description = "User ID") @PathVariable Long id) {
        log.info("Admin: Delete user id={}", id);
        userService.deleteUser(id);
        return ApiResponse.success("User deleted successfully");
    }

    @Operation(summary = "Restore user (Admin)", description = "Restores a previously soft-deleted user. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User restored successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @PatchMapping("/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> restoreUser(
            @Parameter(description = "User ID") @PathVariable Long id) {
        log.info("Admin: Restore user id={}", id);
        userService.restoreUser(id);
        return ApiResponse.success("User restored successfully");
    }

    @Operation(summary = "Toggle user status (Admin)", description = "Toggles a user's status between ACTIVE and BANNED. Requires ADMIN role.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "User status toggled successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "User not found", content = @Content)
    @PatchMapping("/{id}/toggle-status")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<String> toggleUserStatus(
            @Parameter(description = "User ID") @PathVariable Long id) {
        log.info("Admin: Toggle status for user id={}", id);
        userService.toggleUserStatus(id);
        return ApiResponse.success("User status toggled successfully");
    }
}
