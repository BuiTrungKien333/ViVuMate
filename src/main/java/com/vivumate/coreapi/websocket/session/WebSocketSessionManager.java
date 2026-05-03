package com.vivumate.coreapi.websocket.session;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
 * The total number of concurrent sessions per user is limited by
 * {@code vivumate.websocket.session.max-per-user} (default: 5).
 * <p>
 * <b>TTL policy:</b> Each {@code ws_routing:{userId}} key has a TTL that is renewed
 * periodically via {@link #renewRoutingTtl(Long)}. If the server crashes, the key
 * auto-expires after the TTL duration, preventing stale routing entries from
 * causing message delivery failures.
 * <p>
 * <b>Graceful shutdown:</b> On normal shutdown ({@code @PreDestroy}), all local
 * sessions are cleaned up and their routing entries are removed from Redis
 * immediately — no need to wait for TTL expiry.
 * <p>
 * <b>Thread safety:</b> Uses {@link ConcurrentHashMap} with
 * {@link ConcurrentHashMap#newKeySet()} for lock-free concurrent access
 * from WebSocket event listener threads (virtual threads).
 *
 * @see com.vivumate.coreapi.websocket.handler.WebSocketEventListener
 */
@Component
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

    private final StringRedisTemplate redisTemplate;

    /**
     * Unique identifier for this server instance.
     * Used as the value in the Redis routing set.
     * <p>
     * In Kubernetes: typically the pod hostname (e.g., "chat-server-0").
     * In dev: defaults to "local-dev".
     */
    private final String serverId;

    /**
     * TTL duration for {@code ws_routing:{userId}} keys in Redis.
     * <p>
     * This acts as a safety net: if the server crashes without executing
     * {@link #drainAllSessions()}, stale routing entries will auto-expire
     * after this duration. The TTL is renewed periodically by heartbeat-driven
     * calls to {@link #renewRoutingTtl(Long)}.
     * <p>
     * <b>Recommended value:</b> 2-3× the heartbeat renewal interval.
     * With heartbeat every 25s and renewal every ~25-50s, a TTL of 90s
     * ensures the key survives normal heartbeat jitter but expires promptly
     * after a crash.
     */
    private final Duration routingKeyTtl;

    /**
     * Maximum number of concurrent WebSocket sessions allowed per user
     * across THIS server instance.
     * <p>
     * Prevents resource abuse from a single user opening excessive connections
     * (e.g., buggy client reconnect loops, or intentional abuse).
     * When the limit is reached, new connections are rejected with
     * {@code ErrorCode.WS_CONNECTION_LIMIT}.
     * <p>
     * <b>Note:</b> This is a per-server limit. In a multi-server cluster,
     * the effective limit is {@code maxSessionsPerUser × numberOfServers}.
     * For most use cases (phone + web + tablet), 5 is sufficient.
     */
    private final int maxSessionsPerUser;

    public WebSocketSessionManager(
            StringRedisTemplate redisTemplate,
            @Value("${vivumate.server.id:local-dev}") String serverId,
            @Value("${vivumate.websocket.routing.ttl-seconds:90}") int routingTtlSeconds,
            @Value("${vivumate.websocket.session.max-per-user:5}") int maxSessionsPerUser) {
        this.redisTemplate = redisTemplate;
        this.serverId = serverId;
        this.routingKeyTtl = Duration.ofSeconds(routingTtlSeconds);
        this.maxSessionsPerUser = maxSessionsPerUser;
        log.info("Initialized with serverId={}, routingKeyTtl={}s, maxSessionsPerUser={}",
                serverId, routingTtlSeconds, maxSessionsPerUser);
    }

    // ═══════════════════════════════════════════════════════════
    // SESSION REGISTRATION
    // ═══════════════════════════════════════════════════════════

    /**
     * Checks whether a user can accept a new session on this server.
     * <p>
     * Returns {@code false} if the user already has {@code maxSessionsPerUser}
     * sessions on this server instance. The caller (AuthInterceptor or
     * EventListener) should reject the connection when this returns false.
     *
     * @param userId PostgreSQL user ID
     * @return true if user can accept a new session
     */
    public boolean canAcceptSession(Long userId) {
        return getLocalSessionCount(userId) < maxSessionsPerUser;
    }

    /**
     * Registers a new WebSocket session for a user.
     * <p>
     * Called when a STOMP CONNECTED event is received.
     * Updates both local state and Redis routing, and sets TTL on the routing key.
     * <p>
     * <b>Precondition:</b> Caller should check {@link #canAcceptSession(Long)} first.
     * This method does NOT enforce the session limit itself to keep the registration
     * path simple and fast.
     *
     * @param userId    PostgreSQL user ID
     * @param sessionId STOMP session ID (unique per connection)
     */
    public void registerSession(Long userId, String sessionId) {
        localUserSessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(sessionId);

        // Register this server as holding a connection for this user + set TTL
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        redisTemplate.opsForSet().add(routingKey, serverId);
        redisTemplate.expire(routingKey, routingKeyTtl);

        int sessionCount = getLocalSessionCount(userId);
        log.info("Session registered. userId={}, sessionId={}, localSessions={}/{}, serverId={}, ttl={}s",
                userId, sessionId, sessionCount, maxSessionsPerUser, serverId, routingKeyTtl.getSeconds());
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
            log.warn("Remove session: no local sessions found. userId={}, sessionId={}", userId, sessionId);
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

            // Clean up empty key (if this was the last server for this user)
            Long remaining = redisTemplate.opsForSet().size(routingKey);
            if (remaining != null && remaining == 0) {
                redisTemplate.delete(routingKey);
            }

            log.info("All local sessions closed. userId={}, serverId removed from routing", userId);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // TTL MANAGEMENT
    // ═══════════════════════════════════════════════════════════

    /**
     * Renews the TTL on the routing key for a specific user.
     * <p>
     * Called periodically by the heartbeat-driven renewal mechanism in
     * {@link com.vivumate.coreapi.websocket.handler.WebSocketEventListener}.
     * This ensures the routing key stays alive as long as the user has
     * active sessions, and auto-expires promptly after a server crash.
     * <p>
     * <b>Idempotent:</b> Safe to call multiple times — just resets the TTL.
     * If the user has no local sessions, this is a no-op.
     *
     * @param userId PostgreSQL user ID
     */
    public void renewRoutingTtl(Long userId) {
        if (!isUserLocallyConnected(userId)) {
            return;
        }

        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        Boolean renewed = redisTemplate.expire(routingKey, routingKeyTtl);

        if (Boolean.TRUE.equals(renewed)) {
            log.trace("TTL renewed for routing key. userId={}, ttl={}s", userId, routingKeyTtl.getSeconds());
        } else {
            // Key disappeared (e.g., manual DEL or race condition) — re-register
            log.warn("Routing key missing during TTL renewal, re-registering. userId={}", userId);
            redisTemplate.opsForSet().add(routingKey, serverId);
            redisTemplate.expire(routingKey, routingKeyTtl);
        }
    }

    /**
     * Renews TTL for ALL locally connected users using Redis Pipeline.
     * <p>
     * <b>Why pipeline?</b> Without it, N users = N sequential Redis round-trips.
     * Each round-trip costs ~0.3ms (localhost) to ~1-2ms (cross-network).
     * At 10K users, sequential takes ~5s; at 50K users, ~25s — approaching
     * the 30s renewal interval. Pipeline batches all EXPIRE commands into
     * a <b>single network round-trip</b>, reducing cost to O(1) regardless of N.
     * <p>
     * <b>Performance comparison:</b>
     * <pre>
     * Users  | Sequential    | Pipelined
     * -------+---------------+----------
     * 1,000  | ~500ms        | ~5ms
     * 10,000 | ~5s           | ~20ms
     * 50,000 | ~25s (danger) | ~80ms
     * </pre>
     * <p>
     * <b>Self-healing:</b> If EXPIRE returns false (key missing),
     * the affected user is re-registered individually. This is a rare
     * recovery path (manual DEL, Redis failover) so individual calls are OK.
     *
     * @return number of users whose TTL was renewed (including recovered)
     */
    public int renewAllRoutingTtls() {
        // Snapshot user IDs — stable ordered list for result correlation
        List<Long> userIds = List.copyOf(localUserSessions.keySet());
        if (userIds.isEmpty()) {
            return 0;
        }

        RedisSerializer<String> keySerializer = redisTemplate.getStringSerializer();
        long ttlSeconds = routingKeyTtl.getSeconds();

        // Pipeline: batch all EXPIRE commands into 1 network round-trip
        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long userId : userIds) {
                byte[] rawKey = keySerializer.serialize(WS_ROUTING_KEY_PREFIX + userId);
                if (rawKey != null) {
                    connection.keyCommands().expire(rawKey, ttlSeconds);
                }
            }
            return null; // Required: pipeline callback must return null
        });

        // Process results — self-heal missing keys
        int renewed = 0;
        int recovered = 0;
        for (int i = 0; i < results.size() && i < userIds.size(); i++) {
            if (Boolean.TRUE.equals(results.get(i))) {
                renewed++;
            } else if (isUserLocallyConnected(userIds.get(i))) {
                // Key gone but user still connected → re-register
                recoverRoutingKey(userIds.get(i));
                recovered++;
            }
        }

        if (renewed > 0 || recovered > 0) {
            log.debug("Batch TTL renewal completed. renewed={}, recovered={}, total={}",
                    renewed, recovered, userIds.size());
        }
        return renewed + recovered;
    }

    /**
     * Re-registers this server in the routing set for a user whose key
     * was unexpectedly missing during batch TTL renewal.
     * <p>
     * This is a rare recovery path — called only when a key disappeared
     * between registration and renewal (e.g., manual DEL, Redis failover).
     *
     * @param userId PostgreSQL user ID
     */
    private void recoverRoutingKey(Long userId) {
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        redisTemplate.opsForSet().add(routingKey, serverId);
        redisTemplate.expire(routingKey, routingKeyTtl);
        log.warn("Routing key missing, re-registered. userId={}", userId);
    }

    // ═══════════════════════════════════════════════════════════
    // GRACEFUL SHUTDOWN
    // ═══════════════════════════════════════════════════════════

    /**
     * Drains all local sessions and removes this server from all routing keys.
     * <p>
     * Called on application shutdown ({@code @PreDestroy}) to ensure clean
     * removal of routing entries. Without this, stale entries would persist
     * until TTL expiry (up to {@code routingKeyTtl} seconds).
     * <p>
     * <b>Kubernetes integration:</b> K8s sends SIGTERM → Spring triggers
     * {@code @PreDestroy} → this method runs → routing cleaned up → pod dies.
     * The {@code terminationGracePeriodSeconds} in the pod spec should be
     * long enough for this cleanup (typically 5-10s is sufficient).
     */
    @PreDestroy
    public void drainAllSessions() {
        log.info("Draining all local sessions. localUsers={}, totalSessions={}",
                getLocalUserCount(), getTotalLocalSessionCount());

        int drained = 0;
        for (Map.Entry<Long, Set<String>> entry : localUserSessions.entrySet()) {
            Long userId = entry.getKey();
            String routingKey = WS_ROUTING_KEY_PREFIX + userId;

            // Remove this server from the user's routing set
            redisTemplate.opsForSet().remove(routingKey, serverId);

            // Clean up empty key
            Long remaining = redisTemplate.opsForSet().size(routingKey);
            if (remaining != null && remaining == 0) {
                redisTemplate.delete(routingKey);
            }

            drained++;
        }

        localUserSessions.clear();
        log.info("Drain completed. usersCleanedUp={}, serverId={}", drained, serverId);
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
    public Set<String> getServersForUser(Long userId) {
        String routingKey = WS_ROUTING_KEY_PREFIX + userId;
        Set<String> members = redisTemplate.opsForSet().members(routingKey);
        if (members == null || members.isEmpty()) {
            return Collections.emptySet();
        }
        return Collections.unmodifiableSet(members);
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

    /**
     * Returns the configured routing key TTL.
     * Useful for health checks and diagnostics.
     */
    public Duration getRoutingKeyTtl() {
        return routingKeyTtl;
    }

    /**
     * Returns the max sessions per user limit.
     * Useful for diagnostics.
     */
    public int getMaxSessionsPerUser() {
        return maxSessionsPerUser;
    }
}
