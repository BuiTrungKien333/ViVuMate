package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.subdoc.EditHistoryEntry;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

/**
 * Custom repository fragment for {@link MessageDocument}.
 * Contains operations requiring {@code MongoTemplate} for:
 * <ul>
 *   <li>Cursor-based pagination (hot read path)</li>
 *   <li>Full-text search within a conversation</li>
 *   <li>Atomic field-level updates (edit, delete, mentions)</li>
 *   <li>Denormalized sender snapshot sync</li>
 * </ul>
 */
public interface MessageCustomRepository {

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE FEED — Hot Read Path (Cursor-based Pagination)
    // ═══════════════════════════════════════════════════════════

    /**
     * Load messages in a conversation using cursor-based pagination.
     * Returns messages newest-first, excluding messages soft-deleted by/for the requesting user.
     * <p>
     * Uses index: {@code {conversation_id: 1, _id: -1}}
     * <p>
     * Cursor strategy: uses {@code _id} (ObjectId) as cursor since ObjectId
     * contains an embedded timestamp, providing natural chronological ordering
     * without needing a separate {@code created_at} index for pagination.
     *
     * @param conversationId the conversation to load messages from
     * @param currentUserId  the requesting user's PostgreSQL ID (for filtering deleted_for)
     * @param cursor         ObjectId of the last message from the previous page (null for first page)
     * @param pageSize       number of messages per page (recommended: 30)
     * @param clearedAt      Save time to delete user's chat history
     * @return messages sorted newest-first
     */
    List<MessageDocument> findMessagesByConversation(
            ObjectId conversationId, Long currentUserId, ObjectId cursor, Instant clearedAt, int pageSize
    );

    // ═══════════════════════════════════════════════════════════
    //  FULL-TEXT SEARCH
    // ═══════════════════════════════════════════════════════════

    /**
     * Search messages by keyword within a specific conversation.
     * Uses index: {@code {conversation_id: 1, content.text: "text"}}
     *
     * @param conversationId scope the search to this conversation
     * @param keyword        the search term
     * @param pageSize       max results to return
     * @return messages matching the keyword, sorted by text relevance score
     */
    List<MessageDocument> searchMessages(
            ObjectId conversationId, Long currentUserId, Instant clearedAt, String keyword, int pageSize
    );
    // ═══════════════════════════════════════════════════════════
    //  MESSAGE EDITING
    // ═══════════════════════════════════════════════════════════

    /**
     * Edit a message's content. Atomically:
     * <ol>
     *   <li>Sets {@code previous_edit} to the old content snapshot</li>
     *   <li>Replaces {@code content} with the new content</li>
     *   <li>Sets {@code is_edited = true}</li>
     * </ol>
     *
     * @param messageId  the message to edit
     * @param senderUserId the user attempting the edit (must match sender.user_id)
     * @param newContent the updated content
     * @param historyEntry snapshot of the previous content for edit history
     * @return update result (modifiedCount=0 if messageId/senderUserId mismatch)
     */
    UpdateResult editMessage(ObjectId messageId, Long senderUserId,
                             MessageContent newContent, EditHistoryEntry historyEntry);

    // ═══════════════════════════════════════════════════════════
    //  SOFT DELETE
    // ═══════════════════════════════════════════════════════════

    /**
     * Delete a message for a specific user only ("delete for me").
     * Adds the userId to the {@code deleted_for} array.
     * The message remains visible to all other participants.
     */
    UpdateResult deleteForUser(ObjectId messageId, Long userId);

    /**
     * Delete a message for all participants ("delete for everyone").
     * Only the original sender can perform this action.
     * Sets {@code deleted_for_everyone = true} and clears the content.
     */
    UpdateResult deleteForEveryone(ObjectId messageId, Long senderUserId);

    // ═══════════════════════════════════════════════════════════
    //  DENORMALIZED SENDER SNAPSHOT — Sync
    // ═══════════════════════════════════════════════════════════

    /**
     * Update denormalized sender info (fullName, avatarUrl) on recent messages.
     * Called asynchronously when a user updates their profile in PostgreSQL.
     * <p>
     * Only updates messages from the last {@code recentDays} days to avoid
     * excessive write amplification. Older messages keep their historical snapshot.
     *
     * @param userId     the PostgreSQL user ID
     * @param fullName   updated display name
     * @param avatarUrl  updated avatar URL
     * @param recentDays number of days to look back (recommended: 30)
     * @param groupIds   Only update messages belonging to the group.
     * @return number of messages updated
     */
    long updateSenderSnapshot(Long userId, String fullName, String avatarUrl, int recentDays, List<ObjectId> groupIds);
}
