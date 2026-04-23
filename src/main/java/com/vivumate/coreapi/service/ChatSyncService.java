package com.vivumate.coreapi.service;

public interface ChatSyncService {
    void syncUserProfileToMongoDB(Long userId, String fullName, String avatarUrl);
}
