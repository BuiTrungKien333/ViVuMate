package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.repository.mongodb.MessageRepository;
import com.vivumate.coreapi.service.ChatSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j(topic = "CHAT_SYNC_SERVICE")
@RequiredArgsConstructor
public class ChatSyncServiceImpl implements ChatSyncService {

    @Value("${vivumate.chat.sync.recent-days:45}")
    private int recentDays;

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;

    @Async
    public void syncUserProfileToMongoDB(Long userId, String fullName, String avatarUrl) {
        log.info("Starting background sync for user {} to MongoDB...", userId);

        try {
            long updatedConversations = conversationRepository.updateParticipantSnapshot(userId, fullName, avatarUrl);

            // Find Group Chats this user is in
            List<ObjectId> groupIds = conversationRepository.findGroupIdsByUserId(userId)
                    .stream()
                    .map(ConversationDocument::getId)
                    .toList();

            long updatedMessages = messageRepository.updateSenderSnapshot(userId, fullName, avatarUrl, recentDays,
                    groupIds);

            log.info("Background sync completed for user {}. Updated {} conversations and {} recent group messages.",
                    userId, updatedConversations, updatedMessages);

        } catch (Exception e) {
            log.error("FAILED to sync profile to MongoDB for user {}. Reason: {}", userId, e.getMessage(), e);
        }
    }

}
