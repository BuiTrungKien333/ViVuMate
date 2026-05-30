package com.vivumate.coreapi.websocket.handler;

import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.service.MessageService;
import com.vivumate.coreapi.service.MessageService.SendMessageResult;
import com.vivumate.coreapi.websocket.dto.MessageAckResponse;
import com.vivumate.coreapi.websocket.dto.MessageAckResponse.AckStatus;
import com.vivumate.coreapi.websocket.dto.MessageBroadcast;
import com.vivumate.coreapi.websocket.dto.SendMessageRequest;
import com.vivumate.coreapi.websocket.security.StompPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.time.Duration;

/**
 * STOMP message handler for real-time chat messaging.
 * <p>
 * Processes incoming chat messages through a robust pipeline:
 * <ol>
 *   <li><b>Authentication:</b> sender identity extracted from {@link StompPrincipal}
 *       (set during STOMP CONNECT by {@code WebSocketAuthInterceptor})</li>
 *   <li><b>Validation:</b> request field validation (conversationId format, content presence)</li>
 *   <li><b>Idempotency Layer 1 (Redis):</b> SETNX with short TTL prevents duplicate
 *       processing within 5-minute window</li>
 *   <li><b>Persistence:</b> delegates to {@link MessageService} which handles:
 *       membership validation, MongoDB persist, conversation metadata updates,
 *       and unread count increments</li>
 *   <li><b>ACK:</b> confirms delivery to sender via {@code /user/queue/ack}</li>
 *   <li><b>Broadcast:</b> delivers message to ALL participants (multi-device sync)
 *       via {@code /user/queue/messages}</li>
 * </ol>
 *
 * <b>Error handling:</b> All exceptions are caught by
 * {@link WebSocketExceptionHandler} and sent to {@code /user/queue/errors}.
 *
 * <b>Rate limiting:</b> Applied at STOMP frame level by
 * {@code WebSocketRateLimitInterceptor} before this handler is invoked.
 *
 * @see MessageService#sendMessage
 * @see WebSocketExceptionHandler
 */
@Controller
@RequiredArgsConstructor
@Slf4j(topic = "CHAT_MESSAGE_HANDLER")
public class ChatMessageHandler {

    private final MessageService messageService;
    private final SimpMessagingTemplate messagingTemplate;
    private final StringRedisTemplate redisTemplate;

    // ═══════════════════════════════════════════════════════════
    //  REDIS IDEMPOTENCY CONSTANTS
    // ═══════════════════════════════════════════════════════════

    /**
     * Redis key prefix for idempotency: ws_idem:{userId}:{clientMessageId}
     */
    private static final String IDEM_KEY_PREFIX = "ws_idem:";

    /**
     * Short TTL for the initial "processing" claim.
     * If the server crashes during processing, the key auto-expires after 10 seconds,
     * allowing the client to retry without waiting for the full idempotency window.
     */
    private static final Duration CLAIM_TTL = Duration.ofSeconds(10);

    /**
     * Long TTL for the completed idempotency key (with actual messageId).
     * Duplicate sends within this window are short-circuited without touching MongoDB.
     */
    private static final Duration IDEM_TTL = Duration.ofHours(1);

    /**
     * Sentinel value indicating a message is currently being processed.
     */
    private static final String PROCESSING_SENTINEL = "processing";

    // ═══════════════════════════════════════════════════════════
    //  DESTINATION CONSTANTS
    // ═══════════════════════════════════════════════════════════

    private static final String QUEUE_ACK = "/queue/ack";
    private static final String QUEUE_MESSAGES = "/queue/messages";

    private static final int MAX_CHAT_MESSAGE_LENGTH = 5000;

    // ═══════════════════════════════════════════════════════════
    //  MESSAGE HANDLER
    // ═══════════════════════════════════════════════════════════

    /**
     * Handles incoming chat messages from STOMP clients.
     * <p>
     * Client sends a STOMP SEND frame to {@code /app/chat.send} with a JSON body
     * matching {@link SendMessageRequest}. The handler processes the message through
     * validation → idempotency → persistence → ACK → broadcast pipeline.
     *
     * @param request        deserialized message payload
     * @param headerAccessor STOMP headers (contains authenticated Principal)
     */
    @MessageMapping("/chat.send")
    public void handleSendMessage(@Payload SendMessageRequest request,
                                  StompHeaderAccessor headerAccessor) {

        // ── Step 1: Extract authenticated sender ──
        StompPrincipal principal = (StompPrincipal) headerAccessor.getUser();
        if (principal == null) {
            throw new IllegalStateException("StompPrincipal is null — authentication interceptor misconfigured");
        }
        Long senderId = principal.getUserId();

        // ── Step 2: Validate request fields ──
        validateRequest(request);

        String clientMsgId = request.getClientMessageId();
        String idemKey = buildIdempotencyKey(senderId, clientMsgId);

        // ── Step 3: Idempotency Layer 1 — Redis fast-path ──
        // Claim processing slot with short TTL (10s).
        // If another request already claimed it, handle accordingly.
        Boolean claimed = redisTemplate.opsForValue()
                .setIfAbsent(idemKey, PROCESSING_SENTINEL, CLAIM_TTL);

        if (Boolean.FALSE.equals(claimed)) {
            handleDuplicateFromRedis(senderId, clientMsgId, idemKey);
            return;
        }

        // ── Step 4–7: Process, ACK, Broadcast ──
        try {
            processAndDeliver(request, senderId, clientMsgId, idemKey);
        } catch (Exception e) {
            // Cleanup Redis key on failure → allows client retry
            redisTemplate.delete(idemKey);
            throw e; // WebSocketExceptionHandler will handle
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  CORE PROCESSING
    // ═══════════════════════════════════════════════════════════

    /**
     * Core processing pipeline: persist → upgrade Redis → ACK → broadcast.
     */
    private void processAndDeliver(SendMessageRequest request, Long senderId,
                                   String clientMsgId, String idemKey) {

        ObjectId conversationId = new ObjectId(request.getConversationId());

        // ── Step 4: Persist via MessageService ──
        // Service handles: membership validation, sender snapshot, MongoDB persist,
        // lastMessage update, unread/mention increments.
        SendMessageResult result = messageService.sendMessage(
                conversationId,
                senderId,
                clientMsgId,
                request.getContentType(),
                request.getContent(),
                request.getMentions(),
                request.getReplyTo()
        );

        String messageId = result.savedMessage().getId().toHexString();

        // ── Step 5: Upgrade Redis key — short TTL → long TTL with messageId ──
        redisTemplate.opsForValue().set(idemKey, messageId, IDEM_TTL);

        // ── Step 6: ACK to sender ──
        sendAck(senderId, clientMsgId, messageId, AckStatus.SENT);

        // ── Step 7: Broadcast to all participants (multi-device sync) ──
        MessageBroadcast broadcast = MessageBroadcast.from(result.savedMessage());
        broadcastToParticipants(result, broadcast);

        log.info("Message sent: id={}, conv={}, sender={}, recipients={}",
                messageId, request.getConversationId(), senderId, result.allParticipantIds().size());
    }

    // ═══════════════════════════════════════════════════════════
    //  IDEMPOTENCY HANDLING
    // ═══════════════════════════════════════════════════════════

    /**
     * Handles the case when Redis SETNX returns false (key already exists).
     * <p>
     * Two sub-cases:
     * <ul>
     *   <li><b>Value is a messageId:</b> genuine duplicate → ACK(DUPLICATE) with stored messageId</li>
     *   <li><b>Value is "processing" or null:</b> either the original request is still running
     *       (concurrent duplicate within same 10s window) or key expired between SETNX and GET.
     *       Drop silently — the original request will complete and ACK.</li>
     * </ul>
     */
    private void handleDuplicateFromRedis(Long senderId, String clientMsgId, String idemKey) {
        String existingValue = redisTemplate.opsForValue().get(idemKey);

        if (existingValue != null && !PROCESSING_SENTINEL.equals(existingValue)) {
            // Genuine duplicate — existingValue is the messageId
            sendAck(senderId, clientMsgId, existingValue, AckStatus.DUPLICATE);
            log.info("Duplicate message (Redis Layer 1): clientMessageId={}, sender={}, existingMessageId={}",
                    clientMsgId, senderId, existingValue);
        } else {
            // "processing" or expired — original request in flight, drop silently
            log.debug("Concurrent duplicate dropped: clientMessageId={}, sender={}", clientMsgId, senderId);
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  ACK & BROADCAST
    // ═══════════════════════════════════════════════════════════

    /**
     * Sends an ACK to the sender via {@code /user/queue/ack}.
     * Delivered to ALL of the sender's connected sessions (multi-device).
     */
    private void sendAck(Long senderId, String clientMsgId, String messageId, AckStatus status) {
        MessageAckResponse ack = MessageAckResponse.builder()
                .clientMessageId(clientMsgId)
                .messageId(messageId)
                .status(status)
                .build();

        messagingTemplate.convertAndSendToUser(
                senderId.toString(), QUEUE_ACK, ack
        );
    }

    /**
     * Broadcasts the message to ALL conversation participants (including sender)
     * via {@code /user/queue/messages} for multi-device synchronization.
     * <p>
     * <b>Why include sender?</b> The sender may have multiple devices connected.
     * Other devices need to see the message. The sending device deduplicates
     * using {@code clientMessageId} in the broadcast payload.
     * <p>
     * <b>Scalability note:</b> This method is synchronous with SimpleBroker
     * (microseconds per call). For future external broker (RabbitMQ), extract
     * into an async event listener.
     */
    void broadcastToParticipants(SendMessageResult result, MessageBroadcast broadcast) {
        for (Long participantId : result.allParticipantIds()) {
            messagingTemplate.convertAndSendToUser(
                    participantId.toString(), QUEUE_MESSAGES, broadcast
            );
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  VALIDATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Validates the incoming request fields.
     * Throws {@link IllegalArgumentException} which is caught by
     * {@link WebSocketExceptionHandler} and sent to {@code /user/queue/errors}.
     */
    private void validateRequest(SendMessageRequest request) {
        if (request.getClientMessageId() == null || request.getClientMessageId().isBlank()) {
            throw new IllegalArgumentException("clientMessageId is required");
        }
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            throw new IllegalArgumentException("conversationId is required");
        }
        if (request.getConversationId().length() != 24) {
            throw new IllegalArgumentException("conversationId must be a valid 24-character ObjectId hex");
        }
        if (request.getContentType() == null) {
            throw new IllegalArgumentException("contentType is required");
        }
        if (request.getContent() == null) {
            throw new IllegalArgumentException("content is required");
        }

        if (request.getContentType() == ContentType.TEXT) {
            String text = request.getContent().getText();

            if (text.length() > MAX_CHAT_MESSAGE_LENGTH) {
                throw new IllegalArgumentException("Message exceeds maximum length: " + MAX_CHAT_MESSAGE_LENGTH + " character.");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  HELPERS
    // ═══════════════════════════════════════════════════════════

    /**
     * Builds a user-scoped Redis idempotency key.
     * Format: {@code ws_idem:{userId}:{clientMessageId}}
     */
    private String buildIdempotencyKey(Long userId, String clientMessageId) {
        return IDEM_KEY_PREFIX + userId + ":" + clientMessageId;
    }
}
