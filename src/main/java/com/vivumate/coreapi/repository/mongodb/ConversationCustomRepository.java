package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.subdoc.LastMessagePreview;
import com.vivumate.coreapi.document.subdoc.Participant;
import com.mongodb.client.result.UpdateResult;
import org.bson.types.ObjectId;

import java.time.Instant;
import java.util.List;

/**
 * Custom repository fragment for {@link ConversationDocument}.
 * Contains operations that require {@code MongoTemplate} for:
 * <ul>
 *   <li>Atomic field-level updates ({@code $set}, {@code $inc}, {@code $push}, {@code $pull})</li>
 *   <li>Optimized projections (avoid loading full documents on hot paths)</li>
 *   <li>Cursor-based pagination</li>
 * </ul>
 */
public interface ConversationCustomRepository {

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION LIST — Hot Read Path
    // ═══════════════════════════════════════════════════════════

    /**
     * Load conversation list for a user with cursor-based pagination.
     * Returns lightweight projections sorted by {@code lastActivityAt DESC}.
     * <p>
     * Uses index: {@code {participant_ids: 1, last_activity_at: -1}}
     * <p>
     * <b>Cursor strategy:</b> uses {@code lastActivityAt} (Instant) as the primary cursor
     * because conversations are sorted by activity time, NOT by creation time (_id).
     * A conversation created years ago can have very recent activity, so _id does not
     * correlate with display order. {@code cursorId} (ObjectId) serves as a tiebreaker
     * when multiple conversations share the same {@code lastActivityAt} timestamp.
     *
     * @param userId           PostgreSQL user ID
     * @param cursorActivityAt lastActivityAt of the last conversation from the previous page (null for first page)
     * @param cursorId         _id of the last conversation from the previous page (tiebreaker, null for first page)
     * @param pageSize         number of conversations to return
     * @return conversations sorted by latest activity, newest first
     */
    List<ConversationDocument> findConversationsByUserId(
            Long userId, Instant cursorActivityAt, ObjectId cursorId, int pageSize
    );

    // ═══════════════════════════════════════════════════════════
    //  LAST MESSAGE — Subset Pattern update
    // ═══════════════════════════════════════════════════════════

    /**
     * Atomically update the last message preview and last activity timestamp.
     * Called every time a new message is sent to this conversation.
     * <p>
     * Performs:
     * <pre>
     * $set: { last_message: preview, last_activity_at: preview.sentAt, updated_at: now }
     * </pre>
     */
    UpdateResult updateLastMessage(ObjectId conversationId, LastMessagePreview preview);

    // ═══════════════════════════════════════════════════════════
    //  UNREAD COUNTS — Computed Pattern
    // ═══════════════════════════════════════════════════════════

    /**
     * Atomically increment unread count for multiple recipients.
     * Called when a new message is sent (for all participants except the sender).
     * <p>
     * Performs: {@code $inc: { "unread_counts.<userId>": 1 }} for each recipientId.
     */
    UpdateResult incrementUnreadCounts(ObjectId conversationId, List<Long> recipientIds);

    UpdateResult incrementUnreadMentionsCounts(ObjectId conversationId, List<Long> recipientIds);

    /**
     * Reset unread count to 0 for a specific user.
     * Called when the user marks the conversation as read.
     * <p>
     * Performs: {@code $set: { "unread_counts.<userId>": 0 }}
     */
    UpdateResult resetUnreadCount(ObjectId conversationId, Long userId);

    // ═══════════════════════════════════════════════════════════
    //  PARTICIPANT MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    /**
     * Add a participant to a GROUP conversation.
     * Atomically pushes to both {@code participants} and {@code participant_ids},
     * and increments {@code member_count}.
     */
    UpdateResult addParticipant(ObjectId conversationId, Participant participant);

    /**
     * Remove a participant from a GROUP conversation.
     * Atomically pulls from both arrays and decrements {@code member_count}.
     */
    UpdateResult removeParticipant(ObjectId conversationId, Long userId);

    // ═══════════════════════════════════════════════════════════
    //  PARTICIPANT SETTINGS
    // ═══════════════════════════════════════════════════════════

    /**
     * Toggle mute status for a participant in a conversation.
     * Uses positional operator {@code $} to target the specific participant.
     */
    UpdateResult updateParticipantMuteStatus(ObjectId conversationId, Long userId, boolean muted, Instant mutedUntil);

    // ═══════════════════════════════════════════════════════════
    //  DENORMALIZED USER SNAPSHOT — Sync
    // ═══════════════════════════════════════════════════════════

    /**
     * Update denormalized user info (fullName, avatarUrl) across all conversations
     * where the user is a participant. Called asynchronously when the user updates
     * their profile in PostgreSQL.
     *
     * @return number of conversations updated
     */
    long updateParticipantSnapshot(Long userId, String fullName, String avatarUrl);

    // ═══════════════════════════════════════════════════════════
    //  SOFT DELETE
    // ═══════════════════════════════════════════════════════════

    /**
     * Soft-delete a conversation by setting {@code deleted_at}.
     */
    UpdateResult softDelete(ObjectId conversationId);
}
