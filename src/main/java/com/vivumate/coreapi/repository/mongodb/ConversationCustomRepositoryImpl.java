package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.ParticipantRole;
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
    public UpdateResult removeParticipants(ObjectId conversationId, List<Long> targetUserIds) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update();
        // use $in to pull multi objects from the participants array
        update.pull("participants", new org.bson.Document("user_id", new org.bson.Document("$in", targetUserIds)));

        // use pullAll to remove from primitive ID array
        update.pullAll("participantIds", targetUserIds.toArray());

        // update the member count
        update.inc("memberCount", targetUserIds.size() * -1);

        // (Clean up unreadCounts using a loop)
        for (Long userId : targetUserIds) {
            update.unset("unreadCounts." + userId);
            update.unset("unreadMentions." + userId);
        }

        update.set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

    @Override
    public void promoteToAdmin(ObjectId conversationId, Long newAdminId) {
        Query query = new Query(Criteria.where("_id").is(conversationId)
                .and("participants.userId").is(newAdminId));

        Update update = new Update()
                .set("participants.$.role", ParticipantRole.ADMIN)
                .set("updatedAt", Instant.now());

        mongoTemplate.updateFirst(query, update, ConversationDocument.class);
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
        long totalModified = 0;
        // Find all conversations where this user is a participant
        // (UPDATE 1: Update info inside the 'participants' array)
        Query query1 = new Query(Criteria.where("participants.userId").is(userId));

        // Use positional operator to update the matched participant's snapshot
        Update update1 = new Update()
                .set("participants.$.fullName", fullName)
                .set("participants.$.avatarUrl", avatarUrl)
                .set("updatedAt", Instant.now());

        // updateMulti: update ALL matching conversations, not just the first
        UpdateResult result1 = mongoTemplate.updateMulti(query1, update1, ConversationDocument.class);
        totalModified += result1.getModifiedCount();

        // (UPDATE 2: Update 'lastMessage' (ONLY WHEN 2 CONDITIONS ARE MET))
        // (Condition 1: The user updating is indeed the sender of the last message.)
        // (Condition 2: This user DOES NOT have a nickname set in this conversation.)
        Query query2 = new Query(Criteria.where("lastMessage.senderId").is(userId))
                .addCriteria(Criteria.where("participants").elemMatch(
                        Criteria.where("userId").is(userId).and("nickname").isNull()
                ));

        Update update2 = new Update()
                .set("lastMessage.senderName", fullName)
                .set("updatedAt", Instant.now());


        UpdateResult result2 = mongoTemplate.updateMulti(query2, update2, ConversationDocument.class);
        totalModified += result2.getModifiedCount();

        return totalModified;
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

    @Override
    public void updateNickname(ObjectId conversationId, Long userId, String newNickname, String fallbackFullName) {
        Query query1 = new Query(Criteria.where("_id").is(conversationId)
                .and("participants.userId").is(userId));

        Update update1 = new Update().set("updatedAt", Instant.now());

        if (newNickname != null) {
            update1.set("participants.$.nickname", newNickname);
        } else {
            // (If removing nickname, use $unset to cleanly sweep this field from DB)
            update1.unset("participants.$.nickname");
        }

        mongoTemplate.updateFirst(query1, update1, ConversationDocument.class);

        Query query2 = new Query(Criteria.where("_id").is(conversationId)
                .and("last_message.senderId").is(userId));

        Update update2 = new Update().set("updatedAt", Instant.now());

        // (If removing nickname, return the real name (fallbackFullName) to the last message)
        update2.set("last_message.senderName", newNickname != null ? newNickname : fallbackFullName);

        mongoTemplate.updateFirst(query2, update2, ConversationDocument.class);
    }

    @Override
    public UpdateResult updateGroupInfo(ObjectId conversationId, String newName, String newAvatarUrl) {
        Query query = new Query(Criteria.where("_id").is(conversationId));

        Update update = new Update().set("updatedAt", Instant.now());

        if (newName != null) {
            update.set("name", newName);
        }

        if (newAvatarUrl != null) {
            update.set("avatarUrl", newAvatarUrl);
        }

        return mongoTemplate.updateFirst(query, update, ConversationDocument.class);
    }

}
