package com.vivumate.coreapi.repository.mongodb;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.ConversationType;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data MongoDB repository for {@link ConversationDocument}.
 * <p>
 * Provides derived query methods for simple lookups.
 * Complex operations (atomic updates, aggregations) are in
 * {@link ConversationCustomRepository} / {@link ConversationCustomRepositoryImpl}.
 */
@Repository
public interface ConversationRepository
        extends MongoRepository<ConversationDocument, ObjectId>, ConversationCustomRepository {

    // ═══════════════════════════════════════════════════════════
    //  BASIC LOOKUPS — Spring Data derived queries
    // ═══════════════════════════════════════════════════════════

    /**
     * Find a DIRECT conversation by its deterministic hash.
     * Uses index: {@code idx_dm_hash_unique}
     *
     * @param dmHash format "smallerId_largerId"
     */
    Optional<ConversationDocument> findByDmHash(String dmHash);

    /**
     * Check if a DM conversation already exists between two users.
     */
    boolean existsByDmHash(String dmHash);

    /**
     * Find all conversations a user participates in, sorted by latest activity.
     * Uses index: {@code idx_user_conversations_latest}
     * <p>
     * Note: For production with large result sets, prefer the custom
     * {@link ConversationCustomRepository#findConversationsByUserId} method
     * which supports cursor-based pagination and projection.
     */
    List<ConversationDocument> findByParticipantIdsAndDeletedAtIsNullOrderByLastActivityAtDesc(
            Long userId, Pageable pageable
    );

    /**
     * Find conversations by type for a specific user.
     * Uses index: {@code idx_type_activity}
     */
    List<ConversationDocument> findByParticipantIdsAndTypeAndDeletedAtIsNullOrderByLastActivityAtDesc(
            Long userId, ConversationType type, Pageable pageable
    );

    /**
     * Count active conversations for a user.
     */
    long countByParticipantIdsAndDeletedAtIsNull(Long userId);

    /**
     * Find conversation by ID only if the user is a participant (access control).
     */
    @Query("{ '_id': ?0, 'participant_ids': ?1, 'deleted_at': null }")
    Optional<ConversationDocument> findByIdAndParticipantId(ObjectId conversationId, Long userId);
}
