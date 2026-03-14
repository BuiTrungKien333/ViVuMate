package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.dto.request.ChangePasswordRequest;
import com.vivumate.coreapi.dto.request.UserCreationRequest;
import com.vivumate.coreapi.dto.request.UserUpdateRequest;
import com.vivumate.coreapi.dto.response.PageResponse;
import com.vivumate.coreapi.dto.response.UserResponse;
import com.vivumate.coreapi.entity.Role;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.enums.AuthProvider;
import com.vivumate.coreapi.enums.UserStatus;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.mapper.UserMapper;
import com.vivumate.coreapi.repository.RoleRepository;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "USER_SERVICE")
@Transactional(readOnly = true)
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RoleRepository roleRepository;

    // ========= READ ===========
    private User getAuthenticatedUser() {
        return (User) SecurityContextHolder.getContext()
                .getAuthentication()
                .getPrincipal();
    }

    private User getCurrentUserManaged() {
        Long userId = getAuthenticatedUser().getId();
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    @Override
    public UserResponse getMyProfile() {
        User user = getCurrentUserManaged();
        return UserMapper.toUserResponse(user);
    }

    @Override
    public UserResponse getUserById(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        return UserMapper.toUserResponse(user);
    }

    @Override
    public UserResponse getUserByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        return UserMapper.toUserResponse(user);
    }

    @Override
    public PageResponse<UserResponse> searchUsers(String keyword, int page, int size) {
        return null;
    }

    @Override
    public List<UserResponse> getUsersByIds(List<Long> ids) {
        List<User> users = userRepository.findAllById(ids);

        if (users.size() != ids.size()) {
            log.warn("Some user IDs not found. Requested: {}, Found: {}", ids.size(), users.size());
        }

        return users.stream()
                .map(UserMapper::toUserResponse)
                .toList();
    }

    @Override
    public PageResponse<UserResponse> getAllUsers(int page, int size) {
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by("createdAt").descending());
        Page<User> userPage = userRepository.findAll(pageable);

        List<UserResponse> userResponses = userPage.getContent().stream()
                .map(UserMapper::toUserResponse)
                .toList();

        return PageResponse.<UserResponse>builder()
                .currentPage(page)
                .pageSize(size)
                .totalPage(userPage.getTotalPages())
                .totalElements(userPage.getTotalElements())
                .data(userResponses)
                .build();
    }

    // ======= WRITE =======
    private String generateUniqueUsername(String email) {
        String baseUsername = email.substring(0, email.indexOf("@"));
        String username = baseUsername;

        while (userRepository.existsByUsername(username)) {
            int suffix = ThreadLocalRandom.current().nextInt(10000);
            username = baseUsername + "_" + String.format("%04d", suffix);
        }
        return username;
    }

    @Transactional
    @Override
    public UserResponse createUser(UserCreationRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new AppException(ErrorCode.EMAIL_EXISTED);
        }

        Role defaultRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new AppException(ErrorCode.ROLE_NOT_FOUND));

        String username = generateUniqueUsername(request.getEmail());

        User user = User.builder()
                .username(username)
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .email(request.getEmail())
                .gender(request.getGender())
                .dateOfBirth(request.getDateOfBirth())
                .status(UserStatus.ACTIVE)
                .roles(Set.of(defaultRole))
                .provider(AuthProvider.LOCAL)
                .verified(false)
                .online(false)
                .build();

        User savedUser = userRepository.save(user);
        log.info("User created: {}", savedUser.getUsername());

        return UserMapper.toUserResponse(savedUser);
    }

    @Transactional
    @Override
    public UserResponse updateMyProfile(UserUpdateRequest request) {
        User user = getCurrentUserManaged();

        if (request.getFullName() != null)
            user.setFullName(request.getFullName());
        if (request.getBio() != null)
            user.setBio(request.getBio());
        if (request.getAvatarUrl() != null)
            user.setAvatarUrl(request.getAvatarUrl());
        if (request.getCoverUrl() != null)
            user.setCoverUrl(request.getCoverUrl());
        if (request.getGender() != null)
            user.setGender(request.getGender());
        if (request.getDateOfBirth() != null)
            user.setDateOfBirth(request.getDateOfBirth());

        User savedUser = userRepository.save(user);
        log.info("User {} updated profile", user.getUsername());

        return UserMapper.toUserResponse(savedUser);
    }

    @Transactional
    @Override
    public void changePassword(ChangePasswordRequest request) {
        User user = getCurrentUserManaged();

        if (!passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new AppException(ErrorCode.OLD_PASSWORD_INCORRECT);
        }

        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new AppException(ErrorCode.PASSWORDS_DO_NOT_MATCH);
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        log.info("User {} changed password", user.getUsername());
    }

    @Transactional
    @Override
    public UserResponse updateAvatar(String avatarUrl) {
        User user = getCurrentUserManaged();
        user.setAvatarUrl(avatarUrl);

        userRepository.save(user);
        log.info("User {} updated avatar", user.getUsername());
        return UserMapper.toUserResponse(user);
    }

    @Transactional
    @Override
    public UserResponse updateCover(String coverUrl) {
        User user = getCurrentUserManaged();
        user.setCoverUrl(coverUrl);

        userRepository.save(user);
        log.info("User {} updated cover photo", user.getUsername());
        return UserMapper.toUserResponse(user);
    }

    @Transactional
    @Override
    public void updateOnlineStatus(Long userId, boolean online) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setOnline(online);
        user.setLastSeen(LocalDateTime.now());
        userRepository.save(user);

        log.info("User {} is now {}", user.getUsername(), online ? "ONLINE" : "OFFLINE");
    }

    @Transactional
    @Override
    public UserResponse updateUser(Long id, UserUpdateRequest request) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (request.getFullName() != null)
            user.setFullName(request.getFullName());
        if (request.getBio() != null)
            user.setBio(request.getBio());
        if (request.getAvatarUrl() != null)
            user.setAvatarUrl(request.getAvatarUrl());
        if (request.getCoverUrl() != null)
            user.setCoverUrl(request.getCoverUrl());
        if (request.getGender() != null)
            user.setGender(request.getGender());
        if (request.getDateOfBirth() != null)
            user.setDateOfBirth(request.getDateOfBirth());

        User savedUser = userRepository.save(user);
        log.info("Updated user: {}", savedUser.getUsername());

        return UserMapper.toUserResponse(savedUser);
    }

    @Transactional
    @Override
    public void deleteUser(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        userRepository.delete(user);
        userRepository.save(user);
        log.info("User deleted: {}", user.getUsername());
    }

    @Transactional
    @Override
    public void restoreUser(Long id) {
        int updated = userRepository.restoreById(id);
        if (updated == 0) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }

        log.info("User restored, id: {}", id);
    }

    @Transactional
    @Override
    public void toggleUserStatus(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        user.setStatus(user.getStatus() == UserStatus.ACTIVE ? UserStatus.BANNED : UserStatus.ACTIVE);
        userRepository.save(user);
        log.info("User {} status changed to {}", user.getUsername(), user.getStatus());
    }

}
