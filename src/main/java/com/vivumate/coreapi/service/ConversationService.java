package com.vivumate.coreapi.service;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.JoinMethod;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

public interface ConversationService {

    // ═══════════════════════════════════════════════════════════
    //  CREATE CONVERSATIONS
    // ═══════════════════════════════════════════════════════════

    /**
     * Get or create a Direct Message (1-1) conversation between two users.
     * Returns existing conversation if one already exists (idempotent).
     */
    ConversationDocument getOrCreateDirectMessage(Long currentUserId, Long otherUserId);

    /**
     * Create a new GROUP conversation with initial members.
     * Validates: min 3, max 500 members.
     */
    ConversationDocument createGroupConversation(Long creatorUserId, String groupName,
                                                  String groupAvatarUrl, List<Long> memberIds);

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION LIST
    // ═══════════════════════════════════════════════════════════

    /**
     * Load conversation list for a user with cursor-based pagination.
     */
    List<ConversationDocument> getConversationList(Long userId, Instant cursorActivityAt,
                                                    ObjectId cursorId, int pageSize);

    /**
     * Get a single conversation, ensuring the user has access.
     */
    ConversationDocument getConversationById(ObjectId conversationId, Long userId);

    // ═══════════════════════════════════════════════════════════
    //  MEMBER MANAGEMENT (GROUP only)
    // ═══════════════════════════════════════════════════════════

    void addMembers(ObjectId conversationId, Long adminUserId, List<Long> inputMemberIds, JoinMethod method);

    void removeMember(ObjectId conversationId, Long adminUserId, Long targetUserId);

    void leaveGroup(ObjectId conversationId, Long userId);

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION ACTIONS
    // ═══════════════════════════════════════════════════════════

    void clearHistory(ObjectId conversationId, Long userId);

    void dissolveGroup(ObjectId conversationId, Long adminUserId);

    void muteNotifications(ObjectId conversationId, Long userId, int durationInHours);

    void markAsRead(ObjectId conversationId, Long userId);
}
