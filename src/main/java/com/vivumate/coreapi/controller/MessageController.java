package com.vivumate.coreapi.controller;

import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.dto.response.ApiResponse;
import com.vivumate.coreapi.dto.response.CursorPageResponse;
import com.vivumate.coreapi.dto.response.MessageResponse;
import com.vivumate.coreapi.entity.User;
import com.vivumate.coreapi.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for message-related operations.
 * <p>
 * <b>Endpoints:</b>
 * <ul>
 *   <li>{@code GET /api/v1/conversations/{conversationId}/messages} — load message history (cursor pagination)</li>
 *   <li>{@code GET /api/v1/conversations/{conversationId}/messages/search} — full-text search within conversation</li>
 * </ul>
 * <p>
 * <b>Authentication:</b> All endpoints require a valid JWT Bearer token.
 * The authenticated user's ID is used to:
 * <ul>
 *   <li>Filter out messages in the user's {@code deletedFor} list</li>
 *   <li>Respect the user's {@code clearedAt} watermark (messages before this time are hidden)</li>
 * </ul>
 * <p>
 * <b>Pagination strategy:</b> Cursor-based using MongoDB {@code ObjectId} as cursor.
 * This provides O(1) page access regardless of depth — critical for chat apps where
 * users scroll back through thousands of messages.
 *
 * @see MessageService#loadMessages
 * @see MessageService#searchMessages
 */
@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
@RequiredArgsConstructor
@Slf4j(topic = "MESSAGE_CONTROLLER")
@Tag(name = "Messages", description = "Message history and search within conversations")
public class MessageController {

    private final MessageService messageService;

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE HISTORY — Cursor-based pagination
    // ═══════════════════════════════════════════════════════════

    /**
     * Load message history for a conversation with cursor-based pagination.
     * <p>
     * <b>First page:</b> Call without {@code cursor} parameter.
     * <b>Next pages:</b> Use {@code nextCursor} from the previous response.
     * <b>End of history:</b> When {@code hasMore} is {@code false}, stop requesting.
     * <p>
     * Messages are returned in <b>reverse chronological order</b> (newest first),
     * matching the typical chat UI pattern where new messages appear at the bottom
     * and scrolling up loads older messages.
     *
     * @param conversationId the conversation to load messages from (24-char hex ObjectId)
     * @param cursor         optional cursor (ObjectId hex) from previous page's {@code nextCursor}
     * @param limit          number of messages per page (default: 30, max: 50)
     * @param user           the authenticated user (injected by Spring Security)
     * @return paginated message list with cursor metadata
     */
    @Operation(
            summary = "Load message history",
            description = "Returns messages in reverse chronological order (newest first) with cursor-based pagination. "
                    + "Respects user's 'clear history' watermark and 'delete for me' filters."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Messages retrieved successfully")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid conversationId or cursor format", content = @Content)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    @GetMapping
    public ApiResponse<CursorPageResponse<MessageResponse>> getMessages(
            @Parameter(description = "Conversation ID (24-char hex ObjectId)")
            @PathVariable String conversationId,

            @Parameter(description = "Cursor from previous page (ObjectId hex). Omit for first page.")
            @RequestParam(required = false) String cursor,

            @Parameter(description = "Number of messages per page (1-50, default: 30)")
            @RequestParam(defaultValue = "30") @Min(1) @Max(50) int limit,

            @AuthenticationPrincipal User user) {

        // Validate conversationId format
        ObjectId convOid = parseObjectId(conversationId, "conversationId");
        ObjectId cursorOid = cursor != null ? parseObjectId(cursor, "cursor") : null;

        log.debug("Load messages: conversationId={}, cursor={}, limit={}, userId={}",
                conversationId, cursor, limit, user.getId());

        // Fetch limit+1 to determine hasMore without an extra count query
        List<MessageDocument> messages = messageService.loadMessages(
                convOid, user.getId(), cursorOid, limit + 1
        );

        // Build cursor response
        boolean hasMore = messages.size() > limit;
        List<MessageDocument> pageMessages = hasMore ? messages.subList(0, limit) : messages;

        List<MessageResponse> responseItems = pageMessages.stream()
                .map(MessageResponse::from)
                .toList();

        String nextCursor = hasMore
                ? pageMessages.getLast().getId().toHexString()
                : null;

        CursorPageResponse<MessageResponse> page = CursorPageResponse.<MessageResponse>builder()
                .items(responseItems)
                .nextCursor(nextCursor)
                .hasMore(hasMore)
                .size(responseItems.size())
                .build();

        return ApiResponse.success(page);
    }

    // ═══════════════════════════════════════════════════════════
    //  FULL-TEXT SEARCH within conversation
    // ═══════════════════════════════════════════════════════════

    /**
     * Search messages within a conversation using full-text search.
     * <p>
     * Uses MongoDB's text index on {@code content.text} with {@code language: "none"}
     * (language-agnostic tokenization, suitable for Vietnamese and mixed-language content).
     * <p>
     * Results are returned sorted by text relevance score (most relevant first).
     *
     * @param conversationId the conversation to search within
     * @param keyword        the search query string
     * @param limit          max number of results (default: 20, max: 50)
     * @param user           the authenticated user
     * @return list of matching messages sorted by relevance
     */
    @Operation(
            summary = "Search messages in conversation",
            description = "Full-text search within a conversation. Returns messages sorted by relevance. "
                    + "Respects user's 'clear history' watermark."
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Search results returned")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Missing keyword or invalid conversationId", content = @Content)
    @GetMapping("/search")
    public ApiResponse<List<MessageResponse>> searchMessages(
            @Parameter(description = "Conversation ID (24-char hex ObjectId)")
            @PathVariable String conversationId,

            @Parameter(description = "Search keyword", required = true)
            @RequestParam String keyword,

            @Parameter(description = "Max results (1-50, default: 20)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int limit,

            @AuthenticationPrincipal User user) {

        if (keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("Search keyword is required");
        }

        ObjectId convOid = parseObjectId(conversationId, "conversationId");

        log.debug("Search messages: conversationId={}, keyword='{}', limit={}, userId={}",
                conversationId, keyword, limit, user.getId());

        List<MessageDocument> results = messageService.searchMessages(
                convOid, user.getId(), keyword.trim(), limit
        );

        List<MessageResponse> responseItems = results.stream()
                .map(MessageResponse::from)
                .toList();

        return ApiResponse.success(responseItems);
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Parses a hex string to MongoDB ObjectId with a clear error message.
     *
     * @param hex       the 24-character hex string
     * @param fieldName the field name for error messaging
     * @return parsed ObjectId
     * @throws IllegalArgumentException if the format is invalid
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
}
