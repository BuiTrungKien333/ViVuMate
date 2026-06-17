package com.vivumate.coreapi.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.ConversationType;
import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.enums.ParticipantRole;
import com.vivumate.coreapi.document.subdoc.ConversationSettings;
import com.vivumate.coreapi.document.subdoc.LastMessagePreview;
import com.vivumate.coreapi.document.subdoc.Participant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * REST API response DTO for a conversation.
 * <p>
 * Maps from {@link ConversationDocument} to a clean JSON structure for the client.
 * <p>
 * <b>Design decisions:</b>
 * <ul>
 *   <li>ObjectId → String conversion for JSON safety</li>
 *   <li>Nested participant/lastMessage objects flatten internal subdocs</li>
 *   <li>Includes per-user unread counts (keyed by userId as string, matching MongoDB schema)</li>
 *   <li>Excludes internal fields: {@code dmHash}, {@code participantIds} (flat array), {@code deletedAt}</li>
 * </ul>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConversationResponse {

    private final String id;
    private final ConversationType type;

    // Group-specific fields (null for DIRECT)
    private final String name;
    private final String avatarUrl;
    private final ConversationSettings settings;

    // Participants
    private final List<ParticipantResponse> participants;
    private final int memberCount;

    // Last message preview
    private final LastMessageResponse lastMessage;

    // Activity & metadata
    private final Instant lastActivityAt;
    private final Long createdBy;
    private final Instant createdAt;

    // Unread counts (per-user, keyed by userId as String)
    private final Map<String, Integer> unreadCounts;
    private final Map<String, Integer> unreadMentions;

    // ═══════════════════════════════════════════════════════════
    //  NESTED RESPONSE DTOs
    // ═══════════════════════════════════════════════════════════

    /**
     * Participant info visible to other users.
     * Excludes internal fields: {@code clearedAt}, {@code mutedUntil}.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ParticipantResponse {
        private final Long userId;
        private final String username;
        private final String fullName;
        private final String avatarUrl;
        private final ParticipantRole role;
        private final String nickname;
        private final Instant joinedAt;
    }

    /**
     * Last message preview for conversation list rendering.
     * Converts ObjectId to hex string.
     */
    @Getter
    @Builder
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LastMessageResponse {
        private final String messageId;
        private final Long senderId;
        private final String senderName;
        private final String contentPreview;
        private final ContentType contentType;
        private final Instant sentAt;
    }

    // ═══════════════════════════════════════════════════════════
    //  FACTORY METHOD
    // ═══════════════════════════════════════════════════════════

    /**
     * Converts a {@link ConversationDocument} to a REST response.
     *
     * @param doc the MongoDB conversation document
     * @return the REST response DTO
     */
    public static ConversationResponse from(ConversationDocument doc) {
        // Map participants
        List<ParticipantResponse> participantResponses = doc.getParticipants() != null
                ? doc.getParticipants().stream()
                .map(ConversationResponse::mapParticipant)
                .toList()
                : Collections.emptyList();

        // Map last message preview
        LastMessageResponse lastMessageResponse = doc.getLastMessage() != null
                ? mapLastMessage(doc.getLastMessage())
                : null;

        return ConversationResponse.builder()
                .id(doc.getId().toHexString())
                .type(doc.getType())
                .name(doc.getName())
                .avatarUrl(doc.getAvatarUrl())
                .settings(doc.getSettings())
                .participants(participantResponses)
                .memberCount(doc.getMemberCount())
                .lastMessage(lastMessageResponse)
                .lastActivityAt(doc.getLastActivityAt())
                .createdBy(doc.getCreatedBy())
                .createdAt(doc.getCreatedAt())
                .unreadCounts(doc.getUnreadCounts())
                .unreadMentions(doc.getUnreadMentions())
                .build();
    }

    private static ParticipantResponse mapParticipant(Participant p) {
        return ParticipantResponse.builder()
                .userId(p.getUserId())
                .username(p.getUsername())
                .fullName(p.getFullName())
                .avatarUrl(p.getAvatarUrl())
                .role(p.getRole())
                .nickname(p.getNickname())
                .joinedAt(p.getJoinedAt())
                .build();
    }

    private static LastMessageResponse mapLastMessage(LastMessagePreview lmp) {
        return LastMessageResponse.builder()
                .messageId(lmp.getMessageId() != null ? lmp.getMessageId().toHexString() : null)
                .senderId(lmp.getSenderId())
                .senderName(lmp.getSenderName())
                .contentPreview(lmp.getContentPreview())
                .contentType(lmp.getContentType())
                .sentAt(lmp.getSentAt())
                .build();
    }
}
