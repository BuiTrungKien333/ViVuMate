package com.vivumate.coreapi.websocket.handler;

import com.vivumate.coreapi.websocket.security.StompPrincipal;
import com.vivumate.coreapi.websocket.session.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * Listens for WebSocket lifecycle events (connect/disconnect) and
 * coordinates session registration and cleanup.
 * <p>
 * <b>Events handled:</b>
 * <ul>
 *   <li>{@link SessionConnectedEvent} — fired AFTER successful STOMP CONNECT
 *       (i.e., after {@code WebSocketAuthInterceptor} has authenticated the user).
 *       Registers the session in {@link WebSocketSessionManager}.</li>
 *   <li>{@link SessionDisconnectEvent} — fired when the WebSocket connection
 *       closes (normal close, network drop, or server-side termination).
 *       Removes the session from {@link WebSocketSessionManager}.</li>
 * </ul>
 * <p>
 * <b>Why SessionConnectedEvent instead of SessionConnectEvent?</b>
 * {@code SessionConnectEvent} fires when the CONNECT frame is received but
 * BEFORE authentication. {@code SessionConnectedEvent} fires AFTER the
 * CONNECTED reply is sent, guaranteeing that authentication succeeded.
 * <p>
 * <b>Presence integration (Phase 4):</b>
 * This listener will be extended to update Redis presence keys and publish
 * online/offline events when the Presence system is implemented.
 *
 * @see WebSocketSessionManager
 * @see com.vivumate.coreapi.websocket.interceptor.WebSocketAuthInterceptor
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBSOCKET_EVENT_LISTENER")
public class WebSocketEventListener {

    private final WebSocketSessionManager sessionManager;

    /**
     * Handles successful WebSocket connection.
     * <p>
     * Extracts userId from the session attributes (set by
     * {@link com.vivumate.coreapi.websocket.interceptor.WebSocketAuthInterceptor})
     * and registers the session in the session manager.
     *
     * @param event the session connected event
     */
    @EventListener
    public void handleWebSocketConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        // Extract userId from the principal set during CONNECT authentication
        StompPrincipal principal = extractPrincipal(accessor);
        if (principal == null) {
            log.warn("(Skipped) SessionConnected without principal. sessionId={}", sessionId);
            return;
        }

        Long userId = principal.getUserId();
        String username = principal.getUsername();

        // Register session in the session manager (local + Redis)
        sessionManager.registerSession(userId, sessionId);

        log.info("(Connected) WebSocket session established. userId={}, username={}, sessionId={}, " +
                        "localUsers={}, totalLocalSessions={}",
                userId, username, sessionId,
                sessionManager.getLocalUserCount(),
                sessionManager.getTotalLocalSessionCount());

        // TODO [Phase 4]: Update Redis presence → SET presence:{userId} ONLINE
        // TODO [Phase 4]: Publish ONLINE event to friends
    }

    /**
     * Handles WebSocket disconnection.
     * <p>
     * Cleans up the session from the session manager. If this was the user's
     * last session on this server, the server is removed from the Redis
     * routing set.
     *
     * @param event the session disconnect event
     */
    @EventListener
    public void handleWebSocketDisconnected(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        Long userId = extractUserIdFromAttributes(accessor);
        if (userId == null) {
            log.warn("(Skipped) SessionDisconnect without userId. sessionId={}", sessionId);
            return;
        }

        // Remove session from the session manager
        sessionManager.removeSession(userId, sessionId);

        log.info("(Disconnected) WebSocket session closed. userId={}, sessionId={}, " +
                        "remainingLocalSessions={}, closeStatus={}",
                userId, sessionId,
                sessionManager.getLocalSessionCount(userId),
                event.getCloseStatus());

        // TODO [Phase 4]: If last session on ALL servers → SET presence OFFLINE
        // TODO [Phase 4]: Update MongoDB user_chat_profiles.last_seen
        // TODO [Phase 4]: Publish OFFLINE event to friends
    }

    // ═══════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Extracts the StompPrincipal from the event's header accessor.
     * <p>
     * For SessionConnectedEvent, the user principal is available directly.
     */
    private StompPrincipal extractPrincipal(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof StompPrincipal principal) {
            return principal;
        }
        return null;
    }

    /**
     * Extracts userId from session attributes.
     * <p>
     * For SessionDisconnectEvent, the principal may not be available on the
     * accessor directly, so we fall back to session attributes set during
     * the CONNECT phase.
     */
    private Long extractUserIdFromAttributes(StompHeaderAccessor accessor) {
        // Try principal first
        if (accessor.getUser() instanceof StompPrincipal principal) {
            return principal.getUserId();
        }

        // Fallback to session attributes
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs != null) {
            Object userIdObj = sessionAttrs.get("userId");
            if (userIdObj instanceof Long uid) {
                return uid;
            }
        }

        return null;
    }
}
