package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.ConversationType;
import com.vivumate.coreapi.document.enums.JoinMethod;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
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

    private static final int MAX_GROUP_MEMBERS = 100;
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

        Optional<ConversationDocument> existingDm = conversationRepository.findByDmHash(dmHash);
        if (existingDm.isPresent()) {
            return existingDm.get();
        }

        try {
            return createDirectMessage(currentUserId, otherUserId, dmHash);
        } catch (DuplicateKeyException e) {
            log.info("Concurrent DM creation detected for hash {}. Fetching the newly created one.", dmHash);
            return conversationRepository.findByDmHash(dmHash)
                    .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR));
        }
    }

    @Override
    public ConversationDocument createGroupConversation(Long creatorUserId, String groupName,
                                                        String groupAvatarUrl, List<Long> memberIds) {
        // Ensure creator is included in member list
        Set<Long> uniqueMemberIds = new LinkedHashSet<>(memberIds);
        uniqueMemberIds.add(creatorUserId);

        List<Long> allMemberIds = new ArrayList<>(uniqueMemberIds);

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

        Map<String, Integer> initUnreadCounts = new HashMap<>();

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
                .unreadCounts(initUnreadCounts)
                .unreadMentions(initUnreadCounts)
                .build();

        ConversationDocument saved = conversationRepository.save(conversation);

        // TODO: Call MessageService to create System Message: "User X created the group"

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
    public void addMembers(ObjectId conversationId, Long inviterId, List<Long> inputMemberIds, JoinMethod method) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, inviterId);
        validateAdminCanAddMember(conversation, inviterId);

        // Filter duplicate IDs from input
        Set<Long> uniqueInputIds = new LinkedHashSet<>(inputMemberIds);

        // filter people already in group
        Set<Long> existingIds = new HashSet<>(conversation.getParticipantIds());
        List<Long> validIdsToAdd = uniqueInputIds.stream()
                .filter(id -> !existingIds.contains(id))
                .toList();

        // If after filtering no one is left (everyone is already in) -> Exit successfully, no error
        if (validIdsToAdd.isEmpty()) {
            log.info("All requested users are already in the group. Ignoring.");
            return;
        }

        if (conversation.getMemberCount() + validIdsToAdd.size() > MAX_GROUP_MEMBERS) {
            throw new AppException(ErrorCode.CONVERSATION_MEMBER_LIMIT);
        }

        String inviterName = null;
        if (inviterId != null && method == JoinMethod.ADDED_BY) {
            inviterName = conversation.getParticipants().stream()
                    .filter(p -> p.getUserId().equals(inviterId))
                    .map(Participant::getFullName)
                    .findFirst()
                    .orElse("Unknown");
        }

        // Fetch user snapshot from PostgreSQL
        Map<Long, UserMiniResponse> userMap = fetchUserMap(validIdsToAdd);
        Instant now = Instant.now();
        final String finalInviterName = inviterName;

        List<Participant> newParticipants = new ArrayList<>();

        for (Long id : validIdsToAdd) {
            UserMiniResponse user = userMap.get(id);
            if (user == null) throw new AppException(ErrorCode.USER_NOT_FOUND);

            newParticipants.add(Participant.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .fullName(user.getFullName())
                    .avatarUrl(user.getAvatarUrl())
                    .role(ParticipantRole.MEMBER)
                    .joinedAt(now)
                    .addedByUserId(inviterId)
                    .addedByFullName(finalInviterName)
                    .joinMethod(method)
                    .build());
        }

        // Atomic capacity check: memberCount + size(input) < MAX embedded in query predicate
        long modified = conversationRepository.addMultipleParticipants(
                conversationId, newParticipants, validIdsToAdd, MAX_GROUP_MEMBERS
        ).getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_MEMBER_LIMIT);
        }

        log.info("Added {} members to group {}", validIdsToAdd.size(), conversationId);

        // TODO: Publish event for push notification (Spring ApplicationEvent or Message Queue)
        // eventPublisher.publishEvent(new MemberAddedEvent(conversationId, newMemberUserId, adminUserId));
    }

    @Override
    public void removeMembers(ObjectId conversationId, Long adminUserId, List<Long> inputMemberIds) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, adminUserId);
        validateAdminCanDeleteMembers(conversation, adminUserId);

        // (Filter valid IDs (only remove people who are actually in the group))
        Set<Long> existingIds = new HashSet<>(conversation.getParticipantIds());

        List<Long> validIdsToRemove = inputMemberIds.stream()
                .distinct()
                .filter(existingIds::contains)
                .filter(id -> !id.equals(adminUserId))
                .toList();

        if (validIdsToRemove.isEmpty()) {
            log.info("No valid members to remove for conversationId={}", conversationId);
            return;
        }

        long modified = conversationRepository.removeParticipants(conversationId, validIdsToRemove)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.PARTICIPANT_NOT_FOUND);
        }

        log.info("Members removed: conversationId={}, targetCount={}, removedBy={}",
                conversationId, validIdsToRemove.size(), adminUserId);

        // TODO: Publish System Message (e.g., "Admin Kien removed Binh from the group")
    }

    @Override
    public void leaveGroup(ObjectId conversationId, Long userId, Long nextAdminId) {
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, userId);

        // check if the person wanting to leave is the Admin
        boolean isAdmin = conversation.getParticipants().stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

        if (isAdmin) {
            if(conversation.getMemberCount() > 1) {
                if(nextAdminId == null) {
                    throw new AppException(ErrorCode.MUST_TRANSFER_ADMIN_BEFORE_LEAVING);
                }

                boolean isNextAdminValid = conversation.getParticipants().stream()
                        .anyMatch(p -> p.getUserId().equals(nextAdminId));

                if (!isNextAdminValid || nextAdminId.equals(userId)) {
                    throw new AppException(ErrorCode.PARTICIPANT_INVALID);
                }

                conversationRepository.promoteToAdmin(conversationId, nextAdminId);
                log.info("Admin right transferred from {} to {} in group {}", userId, nextAdminId, conversationId);
            } else {
                dissolveGroup(conversationId, userId);
                log.info("The last admin is leaving. Group {} will be empty.", conversationId);
            }
        }

        long modified = conversationRepository.removeParticipants(conversationId, List.of(userId))
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.PARTICIPANT_NOT_FOUND);
        }

        log.info("User left group: conversationId={}, userId={}", conversationId, userId);

        // (3. TODO: Fire System Message (E.g.: "Kien left the group", "Binh became Admin"))
    }

    @Override
    public void updateGroupInfo(ObjectId conversationId, Long currentUserId, String newName, String newAvatarUrl) {
        // (Fetch group info and ensure the user is in this group)
        ConversationDocument conversation = getConversationAndValidateGroup(conversationId, currentUserId);
        validateAdminCanEditInfoGroup(conversation, currentUserId);

        String finalName = (newName != null && !newName.isBlank()) ? newName.trim() : null;
        String finalAvatar = (newAvatarUrl != null && !newAvatarUrl.isBlank()) ? newAvatarUrl.trim() : null;

        if (finalName == null && finalAvatar == null) {
            log.info("Update group info ignored: No valid data provided for conversationId={}", conversationId);
            return;
        }

        long modified = conversationRepository.updateGroupInfo(conversationId, finalName, finalAvatar)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        log.info("Group info updated: conversationId={}, updatedBy={}, newName={}, newAvatar={}",
                conversationId, currentUserId, finalName, finalAvatar);

        // 5. TODO: Bắn System Message
        // (Example: If name -> "Kien changed the group name to X". If avatar -> "Kien changed the group photo")
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
        validateAdminCanDissolveGroup(conversation, adminUserId);

        long modified = conversationRepository.softDelete(conversationId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.CONVERSATION_NOT_FOUND);
        }

        log.info("Group dissolved: conversationId={}, by adminUserId={}", conversationId, adminUserId);
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

    @Override
    public void changeNickName(ObjectId conversationId, Long userId, String nickname) {
        ConversationDocument conversation = getConversationById(conversationId, userId);

        String fallbackFullName = conversation.getParticipants().stream()
                .filter(p -> p.getUserId().equals(userId))
                .map(Participant::getFullName)
                .findFirst()
                .orElseThrow(() -> new AppException(ErrorCode.PARTICIPANT_NOT_FOUND));

        // (Routing logic: Set new or Remove?)
        boolean isRemoving = (nickname == null || nickname.trim().isEmpty());
        String finalNickname = isRemoving ? null : nickname.trim();

        conversationRepository.updateNickname(conversationId, userId, finalNickname, fallbackFullName);

        log.info("Nickname updated: conversationId={}, targetUserId={}, newNickname={}",
                conversationId, userId, finalNickname);
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
                    if (user == null)
                        throw new AppException(ErrorCode.USER_NOT_FOUND);
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

        Map<String, Integer> initUnreadCounts = Map.of(
                String.valueOf(currentUserId), 0,
                String.valueOf(otherUserId), 0
        );

        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.DIRECT)
                .participants(participants)
                .participantIds(memberIds)
                .memberCount(2)
                .lastActivityAt(now)
                .dmHash(dmHash)
                .createdBy(currentUserId)
                .unreadCounts(initUnreadCounts)
                .unreadMentions(initUnreadCounts)
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
    private void validateAdminCanAddMember(ConversationDocument conversation, Long userId) {
        if (conversation.getSettings().isJoinApprovalRequired()) {
            boolean isAdmin = conversation.getParticipants().stream()
                    .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

            if (!isAdmin) {
                // TODO: create require to join group
                throw new AppException(ErrorCode.CONVERSATION_ADMIN_REQUIRED);
            }
        }
    }

    private void validateAdminCanDeleteMembers(ConversationDocument conversation, Long userId) {
        boolean isAdmin = conversation.getParticipants().stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

        if (!isAdmin) {
            throw new AppException(ErrorCode.ONLY_ADMIN_CAN_DELETE_MEMBER);
        }
    }

    private void validateAdminCanEditInfoGroup(ConversationDocument conversation, Long userId) {
        boolean isOnlyAdminEdit = conversation.getSettings().isOnlyAdminsCanEditInfo();

        if (isOnlyAdminEdit) {
            boolean isAdmin = conversation.getParticipants().stream()
                    .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

            if (!isAdmin) {
                throw new AppException(ErrorCode.ONLY_ADMIN_CAN_EDIT_INFO);
            }
        }
    }

    private void validateAdminCanDissolveGroup(ConversationDocument conversation, Long userId) {
        boolean isAdmin = conversation.getParticipants().stream()
                .anyMatch(p -> p.getUserId().equals(userId) && p.getRole() == ParticipantRole.ADMIN);

        if (!isAdmin) {
            throw new AppException(ErrorCode.ONLY_ADMIN_CAN_DISSOLVE_GROUP);
        }
    }

    /**
     * Fetch user snapshots as a map keyed by user ID.
     */
    private Map<Long, UserMiniResponse> fetchUserMap(List<Long> userIds) {
        List<UserMiniResponse> users = userRepository.findChatMembersByIds(userIds);
        return users.stream().collect(Collectors.toMap(UserMiniResponse::getId, Function.identity()));
    }
}
