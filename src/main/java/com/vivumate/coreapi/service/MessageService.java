package com.vivumate.coreapi.service;

import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import com.vivumate.coreapi.document.subdoc.Mention;
import com.vivumate.coreapi.document.subdoc.ReplyToSnapshot;
import com.vivumate.coreapi.document.enums.ContentType;
import org.bson.types.ObjectId;

import java.util.List;

public interface MessageService {

    // ═══════════════════════════════════════════════════════════
    //  SEND MESSAGE
    // ═══════════════════════════════════════════════════════════

    /**
     * Result wrapper for {@link #sendMessage}.
     * <p>
     * Provides the handler with everything it needs to ACK the sender
     * and broadcast to participants — without requiring a second DB query.
     *
     * @param savedMessage       the persisted (or existing duplicate) message
     * @param savedMessage       the persisted message document
     * @param recipientIds       participant IDs excluding the sender (for unread/mention tracking)
     * @param allParticipantIds  all participant IDs including the sender (for multi-device broadcast)
//   * @param duplicate          {@code true} if MongoDB unique index detected a duplicate
     */
    record SendMessageResult(
            MessageDocument savedMessage,
            List<Long> recipientIds,
            List<Long> allParticipantIds
//            boolean duplicate
    ) {}

    /**
     * Send a new message to a conversation.
     * <p>
     * <b>Side effects (on new message):</b>
     * <ul>
     *   <li>Update {@code lastMessage} preview on the conversation</li>
     *   <li>Increment unread counts for all participants except sender</li>
     *   <li>Increment unread mention counts (if applicable)</li>
     * </ul>
     * <b>On duplicate ({@code clientMessageId} already exists):</b>
     * returns the existing message with {@code duplicate=true}, no side effects.
     *
     * @param conversationId  target conversation
     * @param senderUserId    authenticated sender's PostgreSQL user ID
     * @param clientMessageId client-generated UUID for idempotency (Layer 2)
     * @param contentType     message content type discriminator
     * @param content         polymorphic message content
     * @param mentions        optional list of @mentions
     * @param replyTo         optional reply-to snapshot
     * @return result containing saved message, recipient lists, and duplicate flag
     */
    SendMessageResult sendMessage(ObjectId conversationId, Long senderUserId,
                                  String clientMessageId,
                                  ContentType contentType, MessageContent content,
                                  List<Mention> mentions, ReplyToSnapshot replyTo);

    // ═══════════════════════════════════════════════════════════
    //  LOAD MESSAGES
    // ═══════════════════════════════════════════════════════════

    /**
     * Load messages with cursor-based pagination.
     * Respects user's clearedAt watermark.
     */
    List<MessageDocument> loadMessages(ObjectId conversationId, Long currentUserId,
                                        ObjectId cursor, int pageSize);

    /**
     * Full-text search within a conversation.
     * Respects user's clearedAt watermark.
     */
    List<MessageDocument> searchMessages(ObjectId conversationId, Long currentUserId,
                                          String keyword, int pageSize);

    // ═══════════════════════════════════════════════════════════
    //  EDIT & DELETE
    // ═══════════════════════════════════════════════════════════

    /**
     * Result wrapper for {@link #editMessage}.
     * Provides participant IDs for WebSocket broadcast after REST mutation.
     *
     * @param conversationId  the conversation containing the edited message
     * @param messageId       the edited message's ID
     * @param newContent      the updated content (for broadcast payload)
     * @param allParticipantIds  all participant IDs for broadcasting the edit event
     */
    record EditMessageResult(
            ObjectId conversationId,
            ObjectId messageId,
            MessageContent newContent,
            List<Long> allParticipantIds
    ) {}

    /**
     * Result wrapper for {@link #recallMessage}.
     * Provides participant IDs for WebSocket broadcast after REST mutation.
     *
     * @param conversationId  the conversation containing the recalled message
     * @param messageId       the recalled message's ID
     * @param allParticipantIds  all participant IDs for broadcasting the recall event
     */
    record RecallMessageResult(
            ObjectId conversationId,
            ObjectId messageId,
            List<Long> allParticipantIds
    ) {}

    /**
     * Edit a message's content. Only the original sender can edit.
     * If the edited message is the lastMessage, update the preview.
     *
     * @return result containing participant IDs for WebSocket broadcast
     */
    EditMessageResult editMessage(ObjectId conversationId, ObjectId messageId,
                                  Long senderUserId, MessageContent newContent);

    /**
     * Recall (delete for everyone). Only the original sender can recall.
     * If the recalled message is the lastMessage, find and set the penultimate message.
     *
     * @return result containing participant IDs for WebSocket broadcast
     */
    RecallMessageResult recallMessage(ObjectId conversationId, ObjectId messageId, Long senderUserId);

    /**
     * Delete a specific message for the current user only ("delete for me").
     * No broadcast needed — only affects the current user's view.
     */
    void deleteForMe(ObjectId messageId, Long userId);
}
