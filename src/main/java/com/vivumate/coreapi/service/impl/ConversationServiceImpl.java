package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.ConversationType;
import com.vivumate.coreapi.document.enums.ParticipantRole;
import com.vivumate.coreapi.document.subdoc.ConversationSettings;
import com.vivumate.coreapi.document.subdoc.Participant;
import com.vivumate.coreapi.dto.response.UserMiniResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business logic for conversation lifecycle.
 * <p>
 * Design contract with Repository layer:
 * <ul>
 *   <li>All atomic checks (capacity, ownership) are embedded in repository query predicates</li>
 *   <li>Service checks {@code modifiedCount} from {@code UpdateResult} — never re-queries to verify</li>
 *   <li>This avoids TOCTOU (Time-of-Check to Time-of-Use) race conditions</li>
 * </ul>
 */
@Service
@Slf4j(topic = "CONVERSATION_SERVICE")
@RequiredArgsConstructor
public class ConversationServiceImpl implements ConversationService {

    private static final int MAX_GROUP_MEMBERS = 500;
    private static final int MIN_GROUP_MEMBERS = 3;

    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    // ═══════════════════════════════════════════════════════════
    //  CREATE CONVERSATIONS
    // ═══════════════════════════════════════════════════════════

    @Override
    public ConversationDocument getOrCreateDirectMessage(Long currentUserId, Long otherUserId) {
        if (currentUserId.equals(otherUserId)) {
            throw new AppException(ErrorCode.CANNOT_DM_SELF);
        }

        String dmHash = buildDmHash(currentUserId, otherUserId);

        // Idempotent: return existing DM if it already exists
        return conversationRepository.findByDmHash(dmHash)
                .orElseGet(() -> createDirectMessage(currentUserId, otherUserId, dmHash));
    }

    @Override
    public ConversationDocument createGroupConversation(Long creatorUserId, String groupName,
                                                         String groupAvatarUrl, List<Long> memberIds) {
        // Ensure creator is included in member list
        List<Long> allMemberIds = new ArrayList<>(memberIds);
        if (!allMemberIds.contains(creatorUserId)) {
            allMemberIds.addFirst(creatorUserId);
        }

        if (allMemberIds.size() < MIN_GROUP_MEMBERS) {
            throw new AppException(ErrorCode.CONVERSATION_INVALID_MEMBER_COUNT);
        }
        if (allMemberIds.size() > MAX_GROUP_MEMBERS) {
            throw new AppException(ErrorCode.CONVERSATION_MEMBER_LIMIT);
        }

        // Fetch user snapshots from PostgreSQL (source of truth)
        Map<Long, UserMiniResponse> userMap = fetchUserMap(allMemberIds);

        Instant now = Instant.now();
        List<Participant> participants = new ArrayList<>();
        for (Long memberId : allMemberIds) {
            UserMiniResponse user = userMap.get(memberId);
            if (user == null) {
                throw new AppException(ErrorCode.USER_NOT_FOUND);
            }

            participants.add(Participant.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .fullName(user.getFullName())
                    .avatarUrl(user.getAvatarUrl())
                    .role(memberId.equals(creatorUserId) ? ParticipantRole.ADMIN : ParticipantRole.MEMBER)
                    .joinedAt(now)
                    .build());
        }

        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.GROUP)
                .name(groupName)
                .avatarUrl(groupAvatarUrl)
                .participants(participants)
                .participantIds(allMemberIds)
                .memberCount(allMemberIds.size())
                .lastActivityAt(now)
                .settings(ConversationSettings.builder().build())
                .createdBy(creatorUserId)
                .build();

        ConversationDocument saved = conversationRepository.save(conversation);
        log.info("Group conversation created: id={}, name='{}', members={}",
                saved.getId(), groupName, allMemberIds.size());
        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION LIST
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<ConversationDocument> getConversationList(Long userId, Instant cursorActivityAt,
                                                           ObjectId cursorId, int pageSize) {
        return conversationRepository.findConversationsByUserId(userId, cursorActivityAt, cursorId, pageSize);
    }

    @Override
    public ConversationDocument getConversationById(ObjectId conversationId, Long userId) {
        return conversationRepository.findByIdAndParticipantId(conversationId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_NOT_FOUND));
    }

    // ═══════════════════════════════════════════════════════════
    //  MEMBER MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    @Override
    public void addMember(ObjectId conversationId, Long adminUserId, Long newMemberUserId) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, adminUserId);
        validateAdmin(conversation, adminUserId);

        // Check if user is already a participant
        if (conversation.getParticipantIds().contains(newMemberUserId)) {
            throw new AppException(ErrorCode.PARTICIPANT_ALREADY_EXISTS);
        }

        // Fetch user snapshot from PostgreSQL
        UserMiniResponse newMember = fetchUser(newMemberUserId);

        Participant participant = Participant.builder()
                .userId(newMember.getId())
                .username(newMember.getUsername())
                .fullName(newMember.getFullName())
                .avatarUrl(newMember.getAvatarUrl())
                .role(ParticipantRole.MEMBER)
                .joinedAt(Instant.now())
                .build();

        // Atomic capacity check: memberCount < MAX embedded in query predicate
        long modified = conversationRepository.addParticipant(conversationId, participant, MAX_GROUP_MEMBERS)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_MEMBER_LIMIT);
        }

        log.info("Member added: conversationId={}, newMember={}, addedBy={}", conversationId, newMemberUserId, adminUserId);

        // TODO: Publish event for push notification (Spring ApplicationEvent or Message Queue)
        // eventPublisher.publishEvent(new MemberAddedEvent(conversationId, newMemberUserId, adminUserId));
    }

    @Override
    public void removeMember(ObjectId conversationId, Long adminUserId, Long targetUserId) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, adminUserId);
        validateAdmin(conversation, adminUserId);

        // Admin cannot remove themselves via this method (use leaveGroup instead)
        if (adminUserId.equals(targetUserId)) {
            throw new AppException(ErrorCode.INVALID_INPUT);
        }

        long modified = conversationRepository.removeParticipant(conversationId, targetUserId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.PARTICIPANT_NOT_FOUND);
        }

        log.info("Member removed: conversationId={}, target={}, removedBy={}", conversationId, targetUserId, adminUserId);
    }

    @Override
    public void leaveGroup(ObjectId conversationId, Long userId) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, userId);

        // If user is the last admin, they must promote someone else first
        // (simplified: if ADMIN and only 1 admin, block)
        boolean isAdmin = conversation.getParticipants().stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

        if (isAdmin) {
            long adminCount = conversation.getParticipants().stream()
                    .filter(p -> p.getRole() == ParticipantRole.ADMIN)
                    .count();
            if (adminCount <= 1 && conversation.getMemberCount() > 1) {
                throw new AppException(ErrorCode.CONVERSATION_ADMIN_REQUIRED);
            }
        }

        long modified = conversationRepository.removeParticipant(conversationId, userId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.PARTICIPANT_NOT_FOUND);
        }

        log.info("User left group: conversationId={}, userId={}", conversationId, userId);
    }

    // ═══════════════════════════════════════════════════════════
    //  CONVERSATION ACTIONS
    // ═══════════════════════════════════════════════════════════

    @Override
    public void clearHistory(ObjectId conversationId, Long userId) {
        long modified = conversationRepository.updateClearedAt(conversationId, userId, Instant.now())
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        log.info("History cleared: conversationId={}, userId={}", conversationId, userId);
    }

    @Override
    public void dissolveGroup(ObjectId conversationId, Long adminUserId) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, adminUserId);
        validateAdmin(conversation, adminUserId);

        long modified = conversationRepository.softDelete(conversationId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        log.info("Group dissolved: conversationId={}, by adminUserId={}", conversationId, adminUserId);

        // TODO: Schedule background job to hard-delete after 30 days
        // scheduler.schedule(() -> conversationRepository.deleteById(conversationId),
        //     Instant.now().plus(30, ChronoUnit.DAYS));
    }

    @Override
    public void muteNotifications(ObjectId conversationId, Long userId, int durationInHours) {
        boolean muted;
        Instant mutedUntil;

        if (durationInHours < 0) {
            // durationInHours < 0: unmute
            muted = false;
            mutedUntil = null;
        } else if (durationInHours == 0) {
            // durationInHours == 0: mute forever
            muted = true;
            mutedUntil = null;
        } else {
            // durationInHours > 0: mute with duration
            muted = true;
            mutedUntil = Instant.now().plusSeconds((long) durationInHours * 3600);
        }

        long modified = conversationRepository.updateParticipantMuteStatus(
                conversationId, userId, muted, mutedUntil
        ).getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        log.info("Mute updated: conversationId={}, userId={}, muted={}, mutedUntil={}",
                conversationId, userId, muted, mutedUntil);
    }

    @Override
    public void markAsRead(ObjectId conversationId, Long userId) {
        conversationRepository.resetUnreadCount(conversationId, userId);
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Build deterministic DM hash: "smallerId_largerId"
     */
    private String buildDmHash(Long userIdA, Long userIdB) {
        long smaller = Math.min(userIdA, userIdB);
        long larger = Math.max(userIdA, userIdB);
        return smaller + "_" + larger;
    }

    private ConversationDocument createDirectMessage(Long currentUserId, Long otherUserId, String dmHash) {
        List<Long> memberIds = List.of(currentUserId, otherUserId);
        Map<Long, UserMiniResponse> userMap = fetchUserMap(memberIds);

        Instant now = Instant.now();
        List<Participant> participants = memberIds.stream()
                .map(id -> {
                    UserMiniResponse user = userMap.get(id);
                    if (user == null) throw new AppException(ErrorCode.USER_NOT_FOUND);
                    return Participant.builder()
                            .userId(user.getId())
                            .username(user.getUsername())
                            .fullName(user.getFullName())
                            .avatarUrl(user.getAvatarUrl())
                            .role(ParticipantRole.MEMBER)
                            .joinedAt(now)
                            .build();
                })
                .toList();

        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.DIRECT)
                .participants(participants)
                .participantIds(memberIds)
                .memberCount(2)
                .lastActivityAt(now)
                .dmHash(dmHash)
                .createdBy(currentUserId)
                .build();

        ConversationDocument saved = conversationRepository.save(conversation);
        log.info("DM conversation created: id={}, between {} and {}", saved.getId(), currentUserId, otherUserId);
        return saved;
    }

    /**
     * Get conversation and validate it's a GROUP type + user is a participant.
     */
    private ConversationDocument getConversationAndValidateGroup(ObjectId conversationId, Long userId) {
        ConversationDocument conversation = getConversationById(conversationId, userId);
        if (conversation.getType() != ConversationType.GROUP) {
            throw new AppException(ErrorCode.CONVERSATION_NOT_GROUP);
        }
        return conversation;
    }

    /**
     * Validate that the user has ADMIN role in the conversation.
     */
    private void validateAdmin(ConversationDocument conversation, Long userId) {
        boolean isAdmin = conversation.getParticipants().stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);
        if (!isAdmin) {
            throw new AppException(ErrorCode.CONVERSATION_ADMIN_REQUIRED);
        }
    }

    /**
     * Fetch a single user's snapshot from PostgreSQL.
     */
    private UserMiniResponse fetchUser(Long userId) {
        List<UserMiniResponse> results = userRepository.findChatMembersByIds(List.of(userId));
        if (results.isEmpty()) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        return results.getFirst();
    }

    /**
     * Fetch user snapshots as a map keyed by user ID.
     */
    private Map<Long, UserMiniResponse> fetchUserMap(List<Long> userIds) {
        List<UserMiniResponse> users = userRepository.findChatMembersByIds(userIds);
        return users.stream().collect(Collectors.toMap(UserMiniResponse::getId, Function.identity()));
    }
}
