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
     * Send a new message to a conversation.
     * Side effects: update lastMessage, increment unread counts,
     * increment unread mentions (if applicable).
     */
    MessageDocument sendMessage(ObjectId conversationId, Long senderUserId,
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
     * Edit a message's content. Only the original sender can edit.
     * If the edited message is the lastMessage, update the preview.
     */
    void editMessage(ObjectId conversationId, ObjectId messageId,
                     Long senderUserId, MessageContent newContent);

    /**
     * Recall (delete for everyone). Only the original sender can recall.
     * If the recalled message is the lastMessage, find and set the penultimate message.
     */
    void recallMessage(ObjectId conversationId, ObjectId messageId, Long senderUserId);

    /**
     * Delete a specific message for the current user only ("delete for me").
     */
    void deleteForMe(ObjectId messageId, Long userId);
}
