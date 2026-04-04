package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.MessageDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link MessageDocument}.
 * <p>
 * Provides derived query methods for simple lookups.
 * Complex operations (cursor pagination, text search, atomic edits) are in
 * {@link MessageCustomRepository} / {@link MessageCustomRepositoryImpl}.
 */
@Repository
public interface MessageRepository
        extends MongoRepository<MessageDocument, ObjectId>, MessageCustomRepository {

    // ═══════════════════════════════════════════════════════════
    //  BASIC LOOKUPS
    // ═══════════════════════════════════════════════════════════

    /**
     * Find a single message by ID, excluding soft-deleted-for-everyone messages.
     */
    @Query("{ '_id': ?0, 'deleted_for_everyone': false }")
    Optional<MessageDocument> findActiveById(ObjectId messageId);

    /**
     * Find the latest message in a conversation.
     * Used when marking all as read (to get the watermark message ID).
     */
    Optional<MessageDocument> findFirstByConversationIdAndDeletedForEveryoneIsFalseOrderByIdDesc(
            ObjectId conversationId
    );

    /**
     * Count total messages in a conversation (for statistics).
     */
    long countByConversationIdAndDeletedForEveryoneIsFalse(ObjectId conversationId);

    /**
     * Find all messages in a conversation from a specific sender.
     */
    @Query("{ 'conversation_id': ?0, 'sender.user_id': ?1, 'deleted_for_everyone': false }")
    List<MessageDocument> findBySenderInConversation(ObjectId conversationId, Long senderUserId);
}
