package com.vivumate.coreapi.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.subdoc.*;
import lombok.*;

import java.time.Instant;
import java.util.List;

/**
 * REST API response DTO for a chat message.
 * <p>
 * Maps from {@link MessageDocument} to a clean JSON structure for the client.
 * ObjectId fields are converted to hex strings for JSON serialization.
 * <p>
 * <b>Why a separate DTO instead of returning MessageDocument directly?</b>
 * <ul>
 *   <li>Hides internal fields ({@code deletedFor}, {@code score}, MongoDB annotations)</li>
 *   <li>Converts ObjectId to String (JSON-friendly)</li>
 *   <li>Provides a stable API contract independent of document schema changes</li>
 *   <li>Excludes {@code updatedAt}, {@code deletedAt} (internal metadata)</li>
 * </ul>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageResponse {

    private final String id;
    private final String conversationId;
    private final String clientMessageId;

    // Sender
    private final SenderSnapshot sender;

    // Content
    private final ContentType contentType;
    private final MessageContent content;

    // Reply
    private final ReplyToSnapshot replyTo;

    // Mentions
    private final List<Mention> mentions;

    // Edit info
    private final boolean edited;

    // Deleted for everyone
    private final boolean deletedForEveryone;

    // Timestamps
    private final Instant createdAt;

    /**
     * Factory method to convert a {@link MessageDocument} to a REST response.
     * <p>
     * Handles {@code deletedForEveryone} gracefully: if the message was recalled,
     * the content is nullified but the message skeleton is still returned
     * (so the client can render "This message was deleted" placeholder).
     */
    public static MessageResponse from(MessageDocument doc) {
        return MessageResponse.builder()
                .id(doc.getId().toHexString())
                .conversationId(doc.getConversationId().toHexString())
                .clientMessageId(doc.getClientMessageId())
                .sender(doc.getSender())
                .contentType(doc.getContentType())
                .content(doc.isDeletedForEveryone() ? null : doc.getContent())
                .replyTo(doc.getReplyTo())
                .mentions(doc.getMentions())
                .edited(doc.isEdited())
                .deletedForEveryone(doc.isDeletedForEveryone())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
