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

    /**
     * Adds users to a group conversation.
     * Only authorized admins can perform this action.
     */
    void addMembers(ObjectId conversationId, Long adminUserId, List<Long> inputMemberIds, JoinMethod method);

    /**
     * Removes users from a group conversation.
     * Only authorized admins can perform this action.
     */
    void removeMembers(ObjectId conversationId, Long adminUserId, List<Long> inputMemberIds);

    /**
     * Allows a user to leave a group conversation.
     * If the leaving user is an admin, a next admin may be required.
     */
    void leaveGroup(ObjectId conversationId, Long userId, Long nextAdminId);

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION ACTIONS
    // ═══════════════════════════════════════════════════════════

    /**
     * Clears the visible chat history for a specific user in a conversation.
     */
    void clearHistory(ObjectId conversationId, Long userId);

    /**
     * Permanently dissolves a group conversation.
     * Only authorized admins can perform this action.
     */
    void dissolveGroup(ObjectId conversationId, Long adminUserId);

    /**
     * Mutes conversation notifications for a user for the given duration.
     */
    void muteNotifications(ObjectId conversationId, Long userId, int durationInHours);

    /**
     * Marks the conversation as read for the specified user.
     */
    void markAsRead(ObjectId conversationId, Long userId);

    /**
     * Changes or sets the display nickname of a user in a conversation.
     */
    void changeNickName(ObjectId conversationId, Long userId, String nickname);

    void updateGroupInfo(ObjectId conversationId, Long currentUserId, String newName, String newAvatarUrl);
}
