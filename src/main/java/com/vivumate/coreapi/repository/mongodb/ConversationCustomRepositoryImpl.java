package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.subdoc.LastMessagePreview;
import com.vivumate.coreapi.document.subdoc.Participant;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of {@link ConversationCustomRepository} using {@link MongoTemplate}.
 * <p>
 * All write operations use atomic updates ({@code $set}, {@code $inc}, {@code $push}, {@code $pull})
 * to avoid read-modify-write cycles and ensure thread safety under concurrent access.
 */
@Repository
@RequiredArgsConstructor
public class ConversationCustomRepositoryImpl implements ConversationCustomRepository {

    private final MongoTemplate mongoTemplate;

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION LIST — Hot Read Path
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<ConversationDocument> findConversationsByUserId(
            Long userId, Instant cursorActivityAt, ObjectId cursorId, int pageSize) {

        Criteria criteria = Criteria.where("participantIds").is(userId)
                .and("deletedAt").is(null);

        /*
         * Compound cursor strategy for sort {lastActivityAt: -1, _id: -1}:
         *
         * To get the "next page" (older conversations), we need documents where:
         *   (lastActivityAt < cursorActivityAt)                           ← strictly older activity
         *   OR (lastActivityAt == cursorActivityAt AND _id < cursorId)    ← same timestamp, tiebreak by _id
         *
         * This $or pattern guarantees:
         *   - No duplicates between pages
         *   - No skipped documents
         *   - Deterministic ordering even with identical timestamps
         */
        if (cursorActivityAt != null && cursorId != null) {
            criteria = criteria.orOperator(
                    Criteria.where("lastActivityAt").lt(cursorActivityAt),
                    Criteria.where("lastActivityAt").is(cursorActivityAt)
                            .and("_id").lt(cursorId)
            );
        }

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "lastActivityAt")
                        .and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(pageSize);

        // Projection: load only fields needed for conversation list rendering
        query.fields()
                .include("type", "name", "avatarUrl",
                        "lastMessage", "lastActivityAt",
                        "memberCount", "participantIds")
                .include("unreadCounts." + userId)
                .include("unreadMentions." + userId)
                .slice("participants", 3); // Only first 3 avatars for group preview

        return mongoTemplate.find(query, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  LAST MESSAGE — Subset Pattern update
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult updateLastMessage(ObjectId conversationId, LastMessagePreview preview) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update()
                .set("lastMessage", preview)
                .set("lastActivityAt", preview.getSentAt())
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  UNREAD COUNTS — Computed Pattern
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult incrementUnreadCounts(ObjectId conversationId, List<Long> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return UpdateResult.acknowledged(0, 0L, null);
        }

        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update();
        for (Long recipientId : recipientIds) {
            update.inc("unreadCounts." + recipientId, 1);
        }
        update.set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    @Override
    public UpdateResult incrementUnreadMentionsCounts(ObjectId conversationId, List<Long> recipientIds) {
        if (recipientIds == null || recipientIds.isEmpty()) {
            return UpdateResult.acknowledged(0, 0L, null);
        }

        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update();
        for (Long recipientId : recipientIds) {
            update.inc("unreadMentions." + recipientId, 1);
        }
        update.set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    @Override
    public UpdateResult resetUnreadCount(ObjectId conversationId, Long userId) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update()
                .set("unreadCounts." + userId, 0)
                .set("unreadMentions." + userId, 0)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  PARTICIPANT MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult addParticipant(ObjectId conversationId, Participant participant, int maxMembers) {
        // Atomic capacity check: only update if memberCount < maxMembers
        // If group is full, the query matches 0 docs → modifiedCount = 0
        Query query = new Query(Criteria.where("_id").is(conversationId)
                .and("memberCount").lt(maxMembers));

        Update update = new Update()
                .push("participants", participant)
                .addToSet("participantIds", participant.getUserId())
                .inc("memberCount", 1)
                .set("unreadCounts." + participant.getUserId(), 0)
                .set("unreadMentions." + participant.getUserId(), 0)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    @Override
    public UpdateResult removeParticipant(ObjectId conversationId, Long userId) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update()
                .pull("participants", new org.bson.Document("userId", userId))
                .pull("participantIds", userId)
                .inc("memberCount", -1)
                .unset("unreadCounts." + userId)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  PARTICIPANT SETTINGS
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult updateParticipantMuteStatus(ObjectId conversationId, Long userId, boolean muted, Instant mutedUntil) {
        // Use positional operator $ to target the matched participant
        Query query = new Query(Criteria.where("_id").is(conversationId)
                .and("participants.userId").is(userId));

        Update update = new Update()
                .set("participants.$.muted", muted)
                .set("updatedAt", Instant.now());

        if (muted && mutedUntil != null) {
            // (Mute with a duration)
            update.set("participants.$.mutedUntil", mutedUntil);
        } else {
            update.unset("participants.$.mutedUntil");
        }

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  DENORMALIZED USER SNAPSHOT — Sync
    // ═══════════════════════════════════════════════════════════

    @Override
    public long updateParticipantSnapshot(Long userId, String fullName, String avatarUrl) {
        // Find all conversations where this user is a participant
        Query query = new Query(Criteria.where("participants.userId").is(userId));

        // Use positional operator to update the matched participant's snapshot
        Update update = new Update()
                .set("participants.$.fullName", fullName)
                .set("lastMessage.$.senderName", fullName)
                .set("participants.$.avatarUrl", avatarUrl)
                .set("updatedAt", Instant.now());

        // updateMulti: update ALL matching conversations, not just the first
        UpdateResult result = mongoTemplate.updateMulti(query, update, ConversationDocument.class);
        return result.getModifiedCount();
    }

    // ═══════════════════════════════════════════════════════════
    //  SOFT DELETE
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult softDelete(ObjectId conversationId) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update()
                .set("deletedAt", Instant.now())
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  CLEAR HISTORY — Watermark Pattern
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult updateClearedAt(ObjectId conversationId, Long userId, Instant clearedAt) {
        Query query = new Query(Criteria.where("_id").is(conversationId)
                .and("participants.userId").is(userId));

        Update update = new Update()
                .set("participants.$.clearedAt", clearedAt)
                .set("unreadCounts." + userId, 0)
                .set("unreadMentions." + userId, 0)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }
}
