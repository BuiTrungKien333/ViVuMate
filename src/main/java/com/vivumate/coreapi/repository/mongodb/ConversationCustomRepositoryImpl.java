package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.subdoc.LastMessagePreview;
import com.vivumate.coreapi.document.subdoc.Participant;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * Implementation of {@link ConversationCustomRepository} using
 * {@link MongoTemplate}.
 * <p>
 * All write operations use atomic updates ({@code $set}, {@code $inc},
 * {@code $push}, {@code $pull})
 * to avoid read-modify-write cycles and ensure thread safety under concurrent
 * access.
 */
@Repository
@RequiredArgsConstructor
public class ConversationCustomRepositoryImpl implements ConversationCustomRepository {

    private final MongoTemplate mongoTemplate;

    // ═══════════════════════════════════════════════════════════
    // CONVERSATION LIST — Hot Read Path
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<ConversationDocument> findConversationsByUserId(
            Long userId, Instant cursorActivityAt, ObjectId cursorId, int pageSize) {

        // 1. STAGE MATCH: (Basic filter & Compound Cursor)
        Criteria matchCriteria = Criteria.where("participant_ids").is(userId)
                .and("deleted_at").isNull();

        /*
         * Compound cursor strategy for sort {lastActivityAt: -1, _id: -1}:
         *
         * To get the "next page" (older conversations), we need documents where:
         * (lastActivityAt < cursorActivityAt) ← strictly older activity
         * OR (lastActivityAt == cursorActivityAt AND _id < cursorId) ← same timestamp,
         * tiebreak by _id
         *
         * This $or pattern guarantees:
         * - No duplicates between pages
         * - No skipped documents
         * - Deterministic ordering even with identical timestamps
         */
        if (cursorActivityAt != null && cursorId != null) {
            Criteria cursorCriteria = new Criteria().orOperator(
                    Criteria.where("last_activity_at").lt(cursorActivityAt),
                    Criteria.where("last_activity_at").is(cursorActivityAt).and("_id")
                            .lt(cursorId));
            matchCriteria = new Criteria().andOperator(matchCriteria, cursorCriteria);
        }
        MatchOperation matchStage = Aggregation.match(matchCriteria);

        // 2. STAGE SORT: (MUST be immediately after MATCH to enable index scan optimization)
        // Why SORT goes here (before ADD_FIELDS), not after:
        // - MongoDB optimizer can merge consecutive MATCH + SORT into a single index scan
        // on {participant_ids: 1, last_activity_at: -1}
        // - This makes the entire pipeline STREAMING (lazy, doc-by-doc processing)
        // instead of BLOCKING (load ALL docs into RAM, sort, then output)
        // - With LIMIT at the end, MongoDB stops scanning the index once
        // it has collected enough visible conversations (pageSize)
        SortOperation sortStage = Aggregation.sort(
                Sort.by(Sort.Direction.DESC, "last_activity_at")
                        .and(Sort.by(Sort.Direction.DESC, "_id")));

        // 3. STAGE ADD_FIELDS: (Extract the current user's Participant info)
        AddFieldsOperation addMyInfoStage = Aggregation.addFields()
                .addField("myInfo")
                .withValueOf(
                        ArrayOperators.Filter.filter("participants")
                                .as("p")
                                .by(ComparisonOperators.Eq.valueOf("p.user_id")
                                        .equalToValue(userId)))
                .build();

        // 4. STAGE VISIBILITY MATCH: (Filter cleared history conversations using $expr)
        //
        // A conversation is VISIBLE if:
        // (a) User never cleared history (cleared_at is null)
        // OR (b) New activity since clearing (last_activity_at > cleared_at)
        //
        // This implements the "Clear History" UX requirement:
        // - User clears → conversation DISAPPEARS from list
        // - Someone sends a new message → conversation REAPPEARS
        MatchOperation visibilityMatchStage = Aggregation.match(
                new Criteria().orOperator(
                        Criteria.where("myInfo.0.cleared_at").isNull(),
                        new Criteria().expr(
                                ComparisonOperators.Gt.valueOf("last_activity_at")
                                        .greaterThan("myInfo.0.cleared_at"))));

        // 5. STAGE LIMIT: (Pagination — stop scanning once enough results collected)
        LimitOperation limitStage = Aggregation.limit(pageSize);

        // 6. STAGE PROJECTION: (Fetch exactly the necessary fields to optimize payload)
        ProjectionOperation projectStage = Aggregation.project(
                        "type", "name", "avatar_url", "last_message", "last_activity_at",
                        "member_count", "participant_ids")
                .andInclude("unread_counts." + userId, "unread_mentions." + userId)
                .and("participants").slice(3).as("participants");

        // 7. ASSEMBLE PIPELINE
        //
        // Execution flow:
        // MATCH + SORT → index scan {participant_ids, last_activity_at} (streaming)
        // ↓
        // ADD_FIELDS → compute myInfo for one doc (streaming)
        // ↓
        // MATCH → visibility check for one doc (streaming)
        // ↓
        // LIMIT → collected enough? → STOP index scan
        // ↓
        // PROJECT → trim payload
        Aggregation aggregation = Aggregation.newAggregation(
                matchStage,
                sortStage,
                addMyInfoStage,
                visibilityMatchStage,
                limitStage,
                projectStage);

        return mongoTemplate.aggregate(aggregation, "conversations", ConversationDocument.class)
                .getMappedResults();
    }

    // ═══════════════════════════════════════════════════════════
    // LAST MESSAGE — Subset Pattern update
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
    // UNREAD COUNTS — Computed Pattern
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
    // PARTICIPANT MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult addMultipleParticipants(ObjectId conversationId, List<Participant> newParticipants,
                                                List<Long> newParticipantIds, int maxMembers) {
        // Atomic capacity check: only update if memberCount < maxMembers
        // If group is full, the query matches 0 docs → modifiedCount = 0
        // (Current count MUST BE <= (Max - number of people about to be added))
        Query query = new Query(Criteria.where("_id").is(conversationId)
                .and("memberCount").lte(maxMembers - newParticipants.size()));

        Update update = new Update();

        // (Use $each to add multiple elements to array at once)
        update.push("participants").each(newParticipants);
        update.addToSet("participantIds").each(newParticipantIds);

        // (Increase total member count)
        update.inc("memberCount", newParticipants.size());
        update.set("updatedAt", Instant.now());

        for (Long newId : newParticipantIds) {
            update.set("unreadCounts." + newId, 0);
            update.set("unreadMentions." + newId, 0);
        }

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
    // PARTICIPANT SETTINGS
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult updateParticipantMuteStatus(ObjectId conversationId, Long userId, boolean muted,
                                                    Instant mutedUntil) {
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
    // DENORMALIZED USER SNAPSHOT — Sync
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
    // SOFT DELETE
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
    // CLEAR HISTORY — Watermark Pattern
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
