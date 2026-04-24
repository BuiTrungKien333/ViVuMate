package com.vivumate.coreapi.websocket.session;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages WebSocket session-to-user mappings for the current server instance
 * and coordinates cross-server routing via Redis.
 * <p>
 * <b>Architecture:</b>
 * <ul>
 *   <li><b>Local state</b>: {@code ConcurrentHashMap<userId, Set<sessionId>>} —
 *       tracks which sessions belong to which user on THIS server instance.</li>
 *   <li><b>Global state</b> (Redis): {@code ws_routing:{userId} → Set<serverId>} —
 *       tracks which server instances hold connections for a given user.
 *       Used by {@code CrossServerMessageRouter} (Phase 6) to route messages.</li>
 * </ul>
 * <p>
 * <b>Multi-device support:</b> A single user can have multiple active sessions
 * (e.g., phone + web + tablet). Each device creates a separate STOMP session.
 * Messages are delivered to ALL sessions of the recipient user.
 * <p>
 * <b>Thread safety:</b> Uses {@link ConcurrentHashMap} with
 * {@link ConcurrentHashMap#newKeySet()} for lock-free concurrent access
 * from WebSocket event listener threads (virtual threads).
 *
 * @see com.vivumate.coreapi.websocket.handler.WebSocketEventListener
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBSOCKET_SESSION_MANAGER")
public class WebSocketSessionManager {

    private static final String WS_ROUTING_KEY_PREFIX = "ws_routing:";

    /**
     * Local session registry: userId → Set of STOMP sessionIds on this server.
     * <p>
     * Populated on CONNECT, cleaned up on DISCONNECT.
     * ConcurrentHashMap.newKeySet() provides a thread-safe Set implementation.
     */
    private final ConcurrentHashMap<Long, Set<String>> localUserSessions = new ConcurrentHashMap<>();

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Unique identifier for this server instance.
     * Used as the value in the Redis routing set.
     * <p>
     * In Kubernetes: typically the pod hostname (e.g., "chat-server-0").
     * In dev: defaults to "local-dev".
     */
    @Value("${vivumate.server.id:local-dev}")
    private String serverId;

    // ═══════════════════════════════════════════════════════════
    // SESSION REGISTRATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Registers a new WebSocket session for a user.
     * <p>
     * Called when a STOMP CONNECTED event is received.
     * Updates both local state and Redis routing.
     *
     * @param userId    PostgreSQL user ID
     * @param sessionId STOMP session ID (unique per connection)
     */
    public void registerSession(Long userId, String sessionId) {
        localUserSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);

        // Register this server as holding a connection for this user
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        redisTemplate.opsForSet().add(routingKey, serverId);

        int sessionCount = getLocalSessionCount(userId);
        log.info("(Success) Session registered. userId={}, sessionId={}, totalLocalSessions={}, serverId={}",
                userId, sessionId, sessionCount, serverId);
    }

    /**
     * Removes a WebSocket session for a user.
     * <p>
     * Called when a STOMP SESSION_DISCONNECT event is received.
     * If this was the user's last session on this server, removes
     * this server from the Redis routing set.
     *
     * @param userId    PostgreSQL user ID
     * @param sessionId STOMP session ID to remove
     */
    public void removeSession(Long userId, String sessionId) {
        Set<String> sessions = localUserSessions.get(userId);
        if (sessions == null) {
            log.warn("(Skipped) Remove session called but no local sessions found. userId={}, sessionId={}",
                    userId, sessionId);
            return;
        }

        sessions.remove(sessionId);
        log.debug("Session removed. userId={}, sessionId={}, remainingLocalSessions={}",
                userId, sessionId, sessions.size());

        if (sessions.isEmpty()) {
            localUserSessions.remove(userId);

            // No more sessions on this server → remove from Redis routing
            String routingKey = WS_ROUTING_KEY_PREFIX + userId;
            redisTemplate.opsForSet().remove(routingKey, serverId);

            log.info("(Success) All local sessions closed. userId={}, serverId removed from routing", userId);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // QUERY METHODS
    // ═══════════════════════════════════════════════════════════

    /**
     * Checks whether the user has at least one active session on THIS server.
     *
     * @param userId PostgreSQL user ID
     * @return true if user has active local sessions
     */
    public boolean isUserLocallyConnected(Long userId) {
        Set<String> sessions = localUserSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }

    /**
     * Returns the set of STOMP session IDs for a user on THIS server.
     *
     * @param userId PostgreSQL user ID
     * @return unmodifiable set of session IDs, or empty set if none
     */
    public Set<String> getLocalSessions(Long userId) {
        Set<String> sessions = localUserSessions.get(userId);
        return sessions != null ? Collections.unmodifiableSet(sessions) : Collections.emptySet();
    }

    /**
     * Returns the number of active sessions for a user on THIS server.
     *
     * @param userId PostgreSQL user ID
     * @return session count (0 if user has no local sessions)
     */
    public int getLocalSessionCount(Long userId) {
        Set<String> sessions = localUserSessions.get(userId);
        return sessions != null ? sessions.size() : 0;
    }

    /**
     * Returns all server IDs that currently hold WebSocket connections
     * for the given user. Reads from Redis (global state).
     * <p>
     * Used by {@code CrossServerMessageRouter} to determine where
     * to publish messages for cross-server delivery.
     *
     * @param userId PostgreSQL user ID
     * @return set of server IDs, or empty set if user is offline everywhere
     */
    @SuppressWarnings("unchecked")
    public Set<String> getServersForUser(Long userId) {
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        Set<Object> raw = redisTemplate.opsForSet().members(routingKey);
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        // RedisTemplate<String, Object> → values are Objects, safe to cast
        return (Set<String>) (Set<?>) raw;
    }

    /**
     * Checks if a user is connected to ANY server in the cluster.
     * Reads from Redis routing set.
     *
     * @param userId PostgreSQL user ID
     * @return true if user has at least one session on any server
     */
    public boolean isUserOnline(Long userId) {
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        Long size = redisTemplate.opsForSet().size(routingKey);
        return size != null && size > 0;
    }

    // ═══════════════════════════════════════════════════════════
    // METRICS / DEBUG
    // ═══════════════════════════════════════════════════════════

    /**
     * Returns the total number of unique users with active sessions on THIS server.
     * Useful for monitoring dashboards (Prometheus/Grafana).
     *
     * @return count of locally connected users
     */
    public int getLocalUserCount() {
        return localUserSessions.size();
    }

    /**
     * Returns the total number of active sessions on THIS server.
     * Useful for monitoring dashboards.
     *
     * @return total local session count across all users
     */
    public int getTotalLocalSessionCount() {
        return localUserSessions.values().stream()
                .mapToInt(Set::size)
                .sum();
    }

    /**
     * Returns the server ID of this instance.
     * Used for logging and debugging.
     */
    public String getServerId() {
        return serverId;
    }
}
