package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.enums.JoinMethod;
import com.vivumate.coreapi.dto.request.*;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.ConversationResponse;
import com.vivumate.coreapi.dto.response.CursorPageResponse;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.service.ConversationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * REST controller for conversation lifecycle management.
 * <p>
 * <b>Endpoints overview:</b>
 * <pre>
 * ┌─────────────────────────────────────────────────────────────────────┐
 * │ CREATE                                                             │
 * │  POST   /api/v1/conversations/direct          — Create/get DM      │
 * │  POST   /api/v1/conversations/group           — Create group       │
 * │                                                                     │
 * │ READ                                                               │
 * │  GET    /api/v1/conversations                 — List conversations  │
 * │  GET    /api/v1/conversations/{id}            — Get conversation    │
 * │                                                                     │
 * │ UPDATE                                                             │
 * │  PUT    /api/v1/conversations/{id}            — Update group info   │
 * │                                                                     │
 * │ MEMBER MANAGEMENT (GROUP only)                                     │
 * │  POST   /api/v1/conversations/{id}/members    — Add members         │
 * │  DELETE /api/v1/conversations/{id}/members    — Remove members      │
 * │  POST   /api/v1/conversations/{id}/leave      — Leave group         │
 * │  DELETE /api/v1/conversations/{id}/dissolve   — Dissolve group      │
 * │                                                                     │
 * │ ACTIONS                                                            │
 * │  POST   /api/v1/conversations/{id}/read       — Mark as read        │
 * │  POST   /api/v1/conversations/{id}/clear      — Clear history       │
 * │  POST   /api/v1/conversations/{id}/mute       — Mute notifications  │
 * │  PUT    /api/v1/conversations/{id}/nickname    — Change nickname     │
 * └─────────────────────────────────────────────────────────────────────┘
 * </pre>
 * <p>
 * <b>Authentication:</b> All endpoints require a valid JWT Bearer token.
 *
 * @see ConversationService
 */
@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
@Slf4j(topic = "CONVERSATION_CONTROLLER")
@Tag(name = "Conversations", description = "Conversation lifecycle, member management, and user actions")
public class ConversationController {

    private final ConversationService conversationService;

    // ═══════════════════════════════════════════════════════════════
    //  CREATE CONVERSATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Create or get an existing Direct Message (1-1) conversation.
     * <p>
     * <b>Idempotent:</b> If a DM already exists between the two users,
     * it is returned instead of creating a duplicate (uses {@code dmHash} unique index).
     *
     * @param request the target user to start a DM with
     * @param user    the authenticated user
     * @return the DM conversation (new or existing)
     */
    @Operation(
            summary = "Create or get a DM conversation",
            description = "Creates a direct message conversation with the specified user. "
                    + "Idempotent — returns the existing DM if one already exists."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "DM created or fetched successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Cannot DM yourself", content = @Content)
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> createDirectMessage(
            @RequestBody CreateDirectMessageRequest request,
            @AuthenticationPrincipal User user) {

        if (request.getOtherUserId() == null) {
            throw new IllegalArgumentException("otherUserId is required");
        }

        log.info("Create DM: currentUser={}, otherUser={}", user.getId(), request.getOtherUserId());

        ConversationDocument conversation = conversationService.getOrCreateDirectMessage(
                user.getId(), request.getOtherUserId()
        );

        return ApiResponse.success(ConversationResponse.from(conversation));
    }

    /**
     * Create a new group conversation.
     * <p>
     * The creator is automatically added as an ADMIN.
     * <b>Constraints:</b> min 3 members (including creator), max 100 members.
     *
     * @param request group name, avatar, and initial member IDs
     * @param user    the authenticated user (becomes group admin)
     * @return the newly created group conversation
     */
    @Operation(
            summary = "Create a group conversation",
            description = "Creates a new group with the specified members. "
                    + "Creator is automatically added as ADMIN. Min 3, max 100 members."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Group created successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid member count", content = @Content)
    @PostMapping("/group")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ConversationResponse> createGroup(
            @RequestBody CreateGroupRequest request,
            @AuthenticationPrincipal User user) {

        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Group name is required");
        }
        if (request.getMemberIds() == null || request.getMemberIds().isEmpty()) {
            throw new IllegalArgumentException("memberIds is required");
        }

        log.info("Create group: name='{}', creator={}, memberCount={}",
                request.getName(), user.getId(), request.getMemberIds().size());

        ConversationDocument conversation = conversationService.createGroupConversation(
                user.getId(), request.getName().trim(), request.getAvatarUrl(), request.getMemberIds()
        );

        return ApiResponse.success(ConversationResponse.from(conversation));
    }

    // ═══════════════════════════════════════════════════════════════
    //  READ CONVERSATIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * List the authenticated user's conversations with cursor-based pagination.
     * <p>
     * Sorted by {@code lastActivityAt} descending (most recently active first).
     * <p>
     * <b>Cursor system:</b> Uses a composite cursor of {@code (lastActivityAt, _id)}
     * for deterministic pagination even when multiple conversations share the same timestamp.
     * <p>
     * <b>First page:</b> Call without cursor parameters.
     * <b>Next pages:</b> Use {@code cursorActivityAt} and {@code cursorId} from the last item.
     *
     * @param cursorActivityAt ISO-8601 timestamp from the last conversation in previous page
     * @param cursorId         ObjectId hex of the last conversation in previous page
     * @param limit            number of conversations per page (1-50, default: 20)
     * @param user             the authenticated user
     * @return paginated conversation list
     */
    @Operation(
            summary = "List my conversations",
            description = "Returns conversations sorted by last activity (newest first) with cursor-based pagination. "
                    + "Uses composite cursor (lastActivityAt + conversationId) for deterministic ordering."
    )
    @GetMapping
    public ApiResponse<CursorPageResponse<ConversationResponse>> getConversations(
            @Parameter(description = "ISO-8601 timestamp cursor from previous page's last item")
            @RequestParam(required = false) Instant cursorActivityAt,

            @Parameter(description = "ObjectId hex cursor from previous page's last item")
            @RequestParam(required = false) String cursorId,

            @Parameter(description = "Page size (1-50, default: 20)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,

            @AuthenticationPrincipal User user) {

        ObjectId cursorOid = cursorId != null ? parseObjectId(cursorId, "cursorId") : null;

        log.debug("List conversations: userId={}, cursor=({},{}), limit={}",
                user.getId(), cursorActivityAt, cursorId, limit);

        // Fetch limit+1 for hasMore detection
        List<ConversationDocument> conversations = conversationService.getConversationList(
                user.getId(), cursorActivityAt, cursorOid, limit + 1
        );

        boolean hasMore = conversations.size() > limit;
        List<ConversationDocument> pageConversations = hasMore
                ? conversations.subList(0, limit)
                : conversations;

        List<ConversationResponse> items = pageConversations.stream()
                .map(ConversationResponse::from)
                .toList();

        // Build next cursor from the last item
        String nextCursor = null;
        if (hasMore && !pageConversations.isEmpty()) {
            ConversationDocument last = pageConversations.getLast();
            // Encode composite cursor as "timestamp|objectId"
            nextCursor = last.getLastActivityAt().toString() + "|" + last.getId().toHexString();
        }

        CursorPageResponse<ConversationResponse> page = CursorPageResponse.<ConversationResponse>builder()
                .items(items)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(items.size())
                .build();

        return ApiResponse.success(page);
    }

    /**
     * Get a single conversation by ID.
     * Only accessible to participants of the conversation.
     *
     * @param conversationId the conversation's ObjectId hex
     * @param user           the authenticated user
     * @return the conversation details
     */
    @Operation(summary = "Get conversation by ID")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Conversation found")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Conversation not found or user is not a participant", content = @Content)
    @GetMapping("/{conversationId}")
    public ApiResponse<ConversationResponse> getConversation(
            @PathVariable String conversationId,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        ConversationDocument conversation = conversationService.getConversationById(convOid, user.getId());

        return ApiResponse.success(ConversationResponse.from(conversation));
    }

    // ═══════════════════════════════════════════════════════════════
    //  UPDATE GROUP INFO
    // ═══════════════════════════════════════════════════════════════

    /**
     * Update group conversation info (name and/or avatar).
     * <p>
     * Permission depends on group settings:
     * If {@code onlyAdminsCanEditInfo} is true, only ADMINs can update.
     *
     * @param conversationId the group conversation's ID
     * @param request        new name and/or avatar URL
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Update group info",
            description = "Updates group name and/or avatar. Permission depends on group settings."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Group info updated")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Only admins can edit (when restricted)", content = @Content)
    @PutMapping("/{conversationId}")
    public ApiResponse<String> updateGroupInfo(
            @PathVariable String conversationId,
            @RequestBody UpdateGroupInfoRequest request,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.info("Update group info: conversationId={}, by={}", conversationId, user.getId());

        conversationService.updateGroupInfo(convOid, user.getId(), request.getName(), request.getAvatarUrl());

        return ApiResponse.success("Group info updated successfully");
    }

    // ═══════════════════════════════════════════════════════════════
    //  MEMBER MANAGEMENT (GROUP only)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Add members to a group conversation.
     * <p>
     * <b>Permission:</b> If {@code joinApprovalRequired} is true, only ADMINs can add.
     * Otherwise, any member can add others.
     * <p>
     * <b>Idempotent:</b> Members already in the group are silently skipped.
     *
     * @param conversationId the group conversation's ID
     * @param request        list of user IDs to add
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Add members to group",
            description = "Adds members to a group conversation. Already-existing members are silently skipped. "
                    + "May require ADMIN permission depending on group settings."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members added successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Group member limit exceeded", content = @Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Admin required", content = @Content)
    @PostMapping("/{conversationId}/members")
    public ApiResponse<String> addMembers(
            @PathVariable String conversationId,
            @RequestBody MemberActionRequest request,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");
        validateMemberIds(request.getMemberIds());

        log.info("Add members: conversationId={}, count={}, by={}",
                conversationId, request.getMemberIds().size(), user.getId());

        conversationService.addMembers(convOid, user.getId(), request.getMemberIds(), JoinMethod.ADDED_BY);

        return ApiResponse.success("Members added successfully");
    }

    /**
     * Remove members from a group conversation.
     * <p>
     * <b>Permission:</b> Only ADMIN can remove members.
     * Admins cannot remove themselves (use {@code /leave} instead).
     *
     * @param conversationId the group conversation's ID
     * @param request        list of user IDs to remove
     * @param user           the authenticated user (must be ADMIN)
     * @return success response
     */
    @Operation(
            summary = "Remove members from group",
            description = "Removes members from a group conversation. Only ADMIN can perform this action."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Members removed successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Admin required", content = @Content)
    @DeleteMapping("/{conversationId}/members")
    public ApiResponse<String> removeMembers(
            @PathVariable String conversationId,
            @RequestBody MemberActionRequest request,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");
        validateMemberIds(request.getMemberIds());

        log.info("Remove members: conversationId={}, count={}, by={}",
                conversationId, request.getMemberIds().size(), user.getId());

        conversationService.removeMembers(convOid, user.getId(), request.getMemberIds());

        return ApiResponse.success("Members removed successfully");
    }

    /**
     * Leave a group conversation.
     * <p>
     * <b>Admin transfer:</b> If the leaving user is the ADMIN and the group
     * has other members, they MUST provide {@code nextAdminId} to transfer
     * admin rights before leaving.
     * <p>
     * If the admin is the last member, the group is dissolved.
     *
     * @param conversationId the group conversation's ID
     * @param request        optional next admin ID (required if current user is ADMIN)
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Leave a group",
            description = "Leaves a group conversation. If you're the ADMIN, you must transfer admin rights first."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Left the group successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Must transfer admin before leaving", content = @Content)
    @PostMapping("/{conversationId}/leave")
    public ApiResponse<String> leaveGroup(
            @PathVariable String conversationId,
            @RequestBody(required = false) LeaveGroupRequest request,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");
        Long nextAdminId = request != null ? request.getNextAdminId() : null;

        log.info("Leave group: conversationId={}, userId={}, nextAdmin={}",
                conversationId, user.getId(), nextAdminId);

        conversationService.leaveGroup(convOid, user.getId(), nextAdminId);

        return ApiResponse.success("Left the group successfully");
    }

    /**
     * Dissolve (permanently delete) a group conversation.
     * <p>
     * <b>Permission:</b> Only ADMIN can dissolve.
     * This is a soft-delete operation — the document is marked with {@code deletedAt}.
     *
     * @param conversationId the group conversation's ID
     * @param user           the authenticated user (must be ADMIN)
     * @return success response
     */
    @Operation(
            summary = "Dissolve a group",
            description = "Permanently dissolves a group conversation. Only ADMIN can perform this action."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Group dissolved")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Admin required", content = @Content)
    @DeleteMapping("/{conversationId}/dissolve")
    public ApiResponse<String> dissolveGroup(
            @PathVariable String conversationId,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.info("Dissolve group: conversationId={}, by={}", conversationId, user.getId());

        conversationService.dissolveGroup(convOid, user.getId());

        return ApiResponse.success("Group dissolved successfully");
    }

    // ═══════════════════════════════════════════════════════════════
    //  CONVERSATION ACTIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Mark a conversation as read for the current user.
     * <p>
     * Resets the user's unread message count and unread mention count to 0.
     *
     * @param conversationId the conversation's ID
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(summary = "Mark conversation as read", description = "Resets unread count and unread mentions to 0.")
    @PostMapping("/{conversationId}/read")
    public ApiResponse<String> markAsRead(
            @PathVariable String conversationId,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        conversationService.markAsRead(convOid, user.getId());

        return ApiResponse.success("Conversation marked as read");
    }

    /**
     * Clear chat history for the current user.
     * <p>
     * Sets the user's {@code clearedAt} watermark to now.
     * Messages before this timestamp will be hidden from the user's view
     * but remain visible to other participants.
     *
     * @param conversationId the conversation's ID
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Clear chat history",
            description = "Hides all messages before the current timestamp for the requesting user only."
    )
    @PostMapping("/{conversationId}/clear")
    public ApiResponse<String> clearHistory(
            @PathVariable String conversationId,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.info("Clear history: conversationId={}, userId={}", conversationId, user.getId());

        conversationService.clearHistory(convOid, user.getId());

        return ApiResponse.success("History cleared");
    }

    /**
     * Mute or unmute conversation notifications.
     * <p>
     * <b>Duration semantics:</b>
     * <ul>
     *   <li>{@code durationInHours > 0} → Mute for N hours</li>
     *   <li>{@code durationInHours = 0} → Mute forever</li>
     *   <li>{@code durationInHours < 0} → Unmute</li>
     * </ul>
     *
     * @param conversationId  the conversation's ID
     * @param durationInHours mute duration (see semantics above)
     * @param user            the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Mute/unmute notifications",
            description = "Mute: durationInHours > 0 (N hours) or = 0 (forever). Unmute: durationInHours < 0."
    )
    @PostMapping("/{conversationId}/mute")
    public ApiResponse<String> muteNotifications(
            @PathVariable String conversationId,
            @RequestParam int durationInHours,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.info("Mute: conversationId={}, userId={}, duration={}h",
                conversationId, user.getId(), durationInHours);

        conversationService.muteNotifications(convOid, user.getId(), durationInHours);

        String message = durationInHours < 0
                ? "Notifications unmuted"
                : durationInHours == 0
                ? "Notifications muted forever"
                : "Notifications muted for " + durationInHours + " hours";

        return ApiResponse.success(message);
    }

    /**
     * Change or remove the current user's nickname in a conversation.
     * <p>
     * Pass an empty or null {@code nickname} to remove the custom nickname
     * and revert to using {@code fullName}.
     *
     * @param conversationId the conversation's ID
     * @param nickname       new nickname (null or empty to remove)
     * @param user           the authenticated user
     * @return success response
     */
    @Operation(
            summary = "Change nickname",
            description = "Sets or removes the user's display nickname in this conversation."
    )
    @PutMapping("/{conversationId}/nickname")
    public ApiResponse<String> changeNickname(
            @PathVariable String conversationId,
            @RequestParam(required = false) String nickname,
            @AuthenticationPrincipal User user) {

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.info("Change nickname: conversationId={}, userId={}, nickname='{}'",
                conversationId, user.getId(), nickname);

        conversationService.changeNickName(convOid, user.getId(), nickname);

        return ApiResponse.success(nickname != null && !nickname.isBlank()
                ? "Nickname updated"
                : "Nickname removed");
    }

    // ═══════════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Parses a hex string to MongoDB ObjectId with a clear error message.
     */
    private ObjectId parseObjectId(String hex, String fieldName) {
        if (hex == null || hex.length() != 24) {
            throw new IllegalArgumentException(
                    fieldName + " must be a valid 24-character ObjectId hex string"
            );
        }
        try {
            return new ObjectId(hex);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    fieldName + " contains invalid hex characters: " + hex
            );
        }
    }

    /**
     * Validates member ID list is not null or empty.
     */
    private void validateMemberIds(List<Long> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            throw new IllegalArgumentException("memberIds is required and must not be empty");
        }
    }
}
