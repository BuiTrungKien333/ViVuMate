package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.repository.mongodb.impl.MessageCustomRepositoryImpl;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
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
     * Find a single message by ID, ensuring it's not deleted for everyone
     * AND not deleted for the requesting user.
     */
    @Query("{ '_id': ?0, 'deleted_for_everyone': false, 'deleted_for': { $ne: ?1 } }")
    Optional<MessageDocument> findActiveByIdAndUserId(ObjectId messageId, Long currentUserId);

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
     * Find messages in a conversation from a specific sender, with pagination.
     */
    @Query("{ 'conversation_id': ?0, 'sender.user_id': ?1, 'deleted_for_everyone': false, 'deleted_for': { $ne: ?2 } }")
    List<MessageDocument> findBySenderInConversation(
            ObjectId conversationId, Long senderUserId, Long currentUserId, Pageable pageable
    );


    // NOTE: MessageDocument stores clientMessageId for ACK correlation and multi-device dedup.
    // If duplicate messages become a problem, add a unique sparse index on
    // {sender.user_id, client_message_id} and a findBySenderUserIdAndClientMessageId() query here.

    // ═══════════════════════════════════════════════════════════
    //  IDEMPOTENCY (Layer 2 — MongoDB safety net)
    // ═══════════════════════════════════════════════════════════
    /**
     * Find a message by sender and client-generated idempotency key.
     * Used when {@link org.springframework.dao.DuplicateKeyException} is caught
     * during persist — returns the existing message for DUPLICATE ACK.
     */
//    @Query("{ 'sender.user_id': ?0, 'client_message_id': ?1 }")
//    Optional<MessageDocument> findBySenderUserIdAndClientMessageId(Long senderUserId, String clientMessageId);
}
