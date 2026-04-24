package com.vivumate.coreapi.websocket.interceptor;

import com.vivumate.coreapi.websocket.security.StompPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * STOMP Channel Interceptor that enforces per-user rate limiting
 * on outbound SEND frames (i.e., messages sent by clients).
 * <p>
 * <b>Algorithm:</b> Fixed-window counter using Redis INCR + EXPIRE.
 * <ul>
 *   <li>Key: {@code ws_rate:{userId}} — counter per user</li>
 *   <li>TTL: configurable window (default: 60 seconds)</li>
 *   <li>Limit: configurable max sends per window (default: 120)</li>
 * </ul>
 * <p>
 * <b>Why rate-limit WebSocket?</b>
 * Unlike REST APIs behind a reverse proxy (Nginx/ALB) with built-in
 * rate limiting, WebSocket messages bypass HTTP-level throttling.
 * A malicious or buggy client could flood the server with SEND frames.
 * <p>
 * <b>Behavior on limit exceeded:</b>
 * The message is silently dropped (returns {@code null} from {@link #preSend}).
 * A warning log is emitted. The client receives no explicit error —
 * this is intentional to avoid amplifying a flood attack with error frames.
 * For production, consider sending a STOMP ERROR frame or an in-band
 * rate-limit notification.
 */
@Component
@Slf4j(topic = "WEBSOCKET_RATE_LIMIT_INTERCEPTOR")
public class WebSocketRateLimitInterceptor implements ChannelInterceptor {

    private static final String RATE_LIMIT_KEY_PREFIX = "ws_rate:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Maximum number of SEND frames allowed per user within the time window.
     */
    private final int maxSendsPerWindow;

    /**
     * Time window duration for the rate limit counter.
     */
    private final Duration windowDuration;

    public WebSocketRateLimitInterceptor(
            RedisTemplate<String, Object> redisTemplate,
            @Value("${vivumate.websocket.rate-limit.max-sends-per-window:120}") int maxSendsPerWindow,
            @Value("${vivumate.websocket.rate-limit.window-seconds:60}") int windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.maxSendsPerWindow = maxSendsPerWindow;
        this.windowDuration = Duration.ofSeconds(windowSeconds);
    }

    /**
     * Intercepts SEND frames and checks the user's rate limit.
     * Non-SEND frames (SUBSCRIBE, UNSUBSCRIBE, etc.) pass through unchecked.
     *
     * @param message the inbound STOMP message
     * @param channel the message channel
     * @return the original message if within limits, or {@code null} to drop it
     */
    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }

        // Extract userId from the authenticated principal
        StompPrincipal principal = (StompPrincipal) accessor.getUser();
        if (principal == null) {
            // Unauthenticated SEND — should not happen if auth interceptor is configured correctly
            log.warn("(Rejected) SEND from unauthenticated session. sessionId={}", accessor.getSessionId());
            return null;
        }

        Long userId = principal.getUserId();
        String rateLimitKey = RATE_LIMIT_KEY_PREFIX + userId;

        // Atomic increment + set TTL on first access
        Long currentCount = redisTemplate.opsForValue().increment(rateLimitKey);
        if (currentCount == null) {
            // Redis unavailable — fail open (allow the message)
            log.warn("Redis INCR returned null for rate limit. userId={}, allowing message.", userId);
            return message;
        }

        // Set TTL only on the first increment (when counter = 1)
        if (currentCount == 1) {
            redisTemplate.expire(rateLimitKey, windowDuration);
        }

        if (currentCount > maxSendsPerWindow) {
            log.warn("(RateLimited) User exceeded WebSocket SEND limit. userId={}, count={}, limit={}/{}s",
                    userId, currentCount, maxSendsPerWindow, windowDuration.getSeconds());
            return null; // Drop the message silently
        }

        return message;
    }
}
