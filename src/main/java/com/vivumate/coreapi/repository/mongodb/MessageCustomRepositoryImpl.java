package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.subdoc.EditHistoryEntry;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import com.mongodb.client.result.UpdateResult;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.data.mongodb.core.query.TextQuery;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * Implementation of {@link MessageCustomRepository} using {@link MongoTemplate}.
 * <p>
 * Design principles:
 * <ul>
 *   <li>All writes use atomic operators — no read-modify-write cycles</li>
 *   <li>Cursor-based pagination via ObjectId for O(log N) performance</li>
 *   <li>Ownership checks embedded in query predicates (not in application code)
 *       to prevent TOCTOU race conditions</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class MessageCustomRepositoryImpl implements MessageCustomRepository {

    private final MongoTemplate mongoTemplate;

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE FEED — Hot Read Path
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<MessageDocument> findMessagesByConversation(
            ObjectId conversationId, Long currentUserId, ObjectId cursor, Instant clearedAt, int pageSize) {

        Criteria criteria = Criteria.where("conversationId").is(conversationId)
                .and("deletedForEveryone").is(false)
                .and("deletedFor").ne(currentUserId);

        // Cursor-based: fetch messages with _id < cursor (older messages)
        if (cursor != null) {
            criteria = criteria.and("_id").lt(cursor);
        }

        if (clearedAt != null) {
            ObjectId minId = new ObjectId(Date.from(clearedAt));

            if (cursor != null) {
                criteria.gt(minId); // _id < cursor AND _id > minId
            } else {
                criteria = criteria.and("_id").gt(minId);
            }
        }

        Query query = new Query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .limit(pageSize);

        return mongoTemplate.find(query, MessageDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  FULL-TEXT SEARCH
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<MessageDocument> searchMessages(ObjectId conversationId, Long currentUserId, Instant clearedAt, String keyword, int pageSize) {
        // Combine text search with conversation scope
        TextCriteria textCriteria = TextCriteria.forDefaultLanguage().matching(keyword);

        Criteria additionalCriteria = Criteria.where("conversationId").is(conversationId)
                .and("deletedForEveryone").is(false)
                .and("deletedFor").ne(currentUserId);

        if (clearedAt != null) {
            ObjectId minId = new ObjectId(Date.from(clearedAt));
            additionalCriteria = additionalCriteria.and("_id").gt(minId);
        }

        Query query = TextQuery.queryText(textCriteria)
                .sortByScore()
                .addCriteria(additionalCriteria)
                .limit(pageSize);

        // Include text score for relevance ranking
        query.fields().include("score");

        return mongoTemplate.find(query, MessageDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE EDITING
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult editMessage(ObjectId messageId, Long senderUserId,
                                    MessageContent newContent, EditHistoryEntry historyEntry) {
        // Ownership check is part of the query predicate (not in app code)
        // This prevents TOCTOU (Time-Of-Check to Time-Of-Use): if sender doesn't match, modifiedCount = 0
        Query query = new Query(Criteria.where("_id").is(messageId)
                .and("sender.userId").is(senderUserId)
                .and("deletedForEveryone").is(false));

        Update update = new Update()
                .set("content", newContent)
                .set("edited", true)
                .set("previousEdit", historyEntry)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, MessageDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  SOFT DELETE
    // ═══════════════════════════════════════════════════════════

    @Override
    public UpdateResult deleteForUser(ObjectId messageId, Long userId) {
        Query query = new Query(Criteria.where("_id").is(messageId));

        // $addToSet prevents duplicate entries if user deletes twice
        Update update = new Update()
                .addToSet("deletedFor", userId)
                .set("updatedAt", Instant.now());

        return mongoTemplate.updateFirst(query, update, MessageDocument.class);
    }

    @Override
    public UpdateResult deleteForEveryone(ObjectId messageId, Long senderUserId) {
        // Only the original sender can delete for everyone
        Query query = new Query(Criteria.where("_id").is(messageId)
                .and("sender.userId").is(senderUserId));

        Update update = new Update()
                .set("deletedForEveryone", true)
                .set("deletedAt", Instant.now())
                .set("updatedAt", Instant.now())
                .unset("content")
                .unset("mentions")
                .unset("replyTo");

        return mongoTemplate.updateFirst(query, update, MessageDocument.class);
    }

    // ═══════════════════════════════════════════════════════════
    //  DENORMALIZED SENDER SNAPSHOT — Sync
    // ═══════════════════════════════════════════════════════════

    @Override
    public long updateSenderSnapshot(Long userId, String fullName, String avatarUrl, int recentDays, List<ObjectId> groupIds) {
        if (groupIds == null || groupIds.isEmpty())
            return 0;

        // Only update recent messages to limit write amplification
        Instant cutoff = Instant.now().minus(recentDays, ChronoUnit.DAYS);

        // ObjectId embeds timestamp, so we can filter by constructing a min ObjectId for the cutoff
        ObjectId cutoffId = new ObjectId(java.util.Date.from(cutoff));

        Query query = new Query(Criteria.where("sender.userId").is(userId)
                .and("conversationId").in(groupIds)
                .and("_id").gte(cutoffId));

        Update update = new Update()
                .set("sender.fullName", fullName)
                .set("sender.avatarUrl", avatarUrl)
                .set("updatedAt", Instant.now());

        UpdateResult result = mongoTemplate.updateMulti(query, update, MessageDocument.class);
        return result.getModifiedCount();
    }
}
