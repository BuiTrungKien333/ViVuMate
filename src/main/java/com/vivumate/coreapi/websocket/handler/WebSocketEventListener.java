package com.vivumate.coreapi.websocket.handler;

import com.vivumate.coreapi.websocket.security.StompPrincipal;
import com.vivumate.coreapi.websocket.session.WebSocketSessionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.Map;

/**
 * Listens for WebSocket lifecycle events (connect/disconnect) and
 * coordinates session registration, cleanup, and routing TTL renewal.
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
 * <b>TTL renewal strategy:</b>
 * A scheduled task runs periodically to renew the Redis TTL on all
 * {@code ws_routing:{userId}} keys for locally connected users. This ensures:
 * <ul>
 *   <li>Keys stay alive as long as the server is healthy and users are connected</li>
 *   <li>Keys auto-expire (within {@code routing.ttl-seconds}) if the server crashes</li>
 *   <li>No per-heartbeat Redis calls — one batch renewal covers all users</li>
 * </ul>
 * <p>
 * <b>Telemetry:</b> Structured log events with standardized prefixes enable
 * monitoring via log aggregation until Micrometer is integrated (Phase 7):
 * <ul>
 *   <li>{@code SESSION_CONNECT:} — successful WebSocket session established</li>
 *   <li>{@code SESSION_DISCONNECT:} — WebSocket session closed</li>
 *   <li>{@code SESSION_HEARTBEAT:} — periodic routing TTL renewal</li>
 * </ul>
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

    // ═══════════════════════════════════════════════════════════
    // LIFECYCLE EVENTS
    // ═══════════════════════════════════════════════════════════

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
            log.warn("SESSION_CONNECT: No principal. sessionId={}", sessionId);
            return;
        }

        Long userId = principal.getUserId();
        String username = principal.getUsername();

        // Register session in the session manager (local + Redis with TTL)
        sessionManager.registerSession(userId, sessionId);

        log.info("SESSION_CONNECT: userId={}, username={}, sessionId={}, " +
                        "localUsers={}, totalSessions={}",
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
            log.warn("SESSION_DISCONNECT: No userId. sessionId={}", sessionId);
            return;
        }

        // Remove session from the session manager
        sessionManager.removeSession(userId, sessionId);

        log.info("SESSION_DISCONNECT: userId={}, sessionId={}, remainingSessions={}, closeStatus={}",
                userId, sessionId,
                sessionManager.getLocalSessionCount(userId),
                event.getCloseStatus());

        // TODO [Phase 4]: If last session on ALL servers → SET presence OFFLINE
        // TODO [Phase 4]: Update MongoDB user_chat_profiles.last_seen
        // TODO [Phase 4]: Publish OFFLINE event to friends
    }

    // ═══════════════════════════════════════════════════════════
    // TTL RENEWAL — Scheduled Task
    // ═══════════════════════════════════════════════════════════

    /**
     * Periodically renews the Redis TTL for all locally connected users' routing keys.
     * <p>
     * <b>Why batch renewal instead of per-heartbeat renewal?</b>
     * <ul>
     *   <li>STOMP heartbeats are per-connection and happen at the transport layer —
     *       Spring does not expose a per-heartbeat callback for custom logic.</li>
     *   <li>A single scheduled task can renew ALL routing keys in one sweep,
     *       which is more efficient than N individual renewals triggered by N heartbeats.</li>
     *   <li>The renewal interval (30s) and TTL (90s) are tuned so that the key
     *       always survives at least 2 missed renewals before expiring.</li>
     * </ul>
     * <p>
     * <b>Failure scenario:</b>
     * <pre>
     * t=0s   : Key created with TTL=90s
     * t=30s  : Renewal → TTL reset to 90s  ✓
     * t=60s  : Renewal → TTL reset to 90s  ✓
     * t=90s  : Server crashes, no more renewals
     * t=180s : Key expires (90s after last renewal) → stale routing cleaned up
     * </pre>
     * <p>
     * Maximum stale window = TTL duration (90s by default).
     */
    @Scheduled(fixedDelayString = "${vivumate.websocket.routing.renewal-interval-ms:30000}")
    public void renewRoutingKeyTtls() {
        int localUserCount = sessionManager.getLocalUserCount();
        if (localUserCount == 0) {
            return; // No users connected, skip
        }

        int renewed = sessionManager.renewAllRoutingTtls();
        log.debug("SESSION_HEARTBEAT: renewed={}/{}, totalSessions={}",
                renewed, localUserCount, sessionManager.getTotalLocalSessionCount());
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
