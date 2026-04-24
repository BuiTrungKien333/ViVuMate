package com.vivumate.coreapi.websocket.interceptor;

import com.vivumate.coreapi.entity.enums.TokenType;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.security.JwtUtils;
import com.vivumate.coreapi.service.TokenBlacklistService;
import com.vivumate.coreapi.websocket.security.StompPrincipal;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * STOMP Channel Interceptor that authenticates WebSocket connections
 * using JWT tokens during the STOMP CONNECT handshake.
 * <p>
 * <b>Authentication flow:</b>
 * <ol>
 *   <li>Client sends STOMP CONNECT frame with {@code Authorization} header
 *       containing {@code Bearer <accessToken>}</li>
 *   <li>Interceptor extracts and validates the JWT token</li>
 *   <li>Checks if token is blacklisted (logged-out tokens in Redis)</li>
 *   <li>Extracts userId and username from JWT claims</li>
 *   <li>Creates {@link StompPrincipal} and sets it on the session</li>
 *   <li>Stores userId and username in session attributes for later use</li>
 * </ol>
 * <p>
 * <b>Why authenticate only on CONNECT?</b>
 * STOMP CONNECT is the handshake phase — once authenticated, the session is
 * trusted for its lifetime. This avoids the overhead of validating JWT on
 * every single STOMP frame (SEND, SUBSCRIBE, etc.), which would be
 * extremely costly for high-throughput chat.
 * <p>
 * <b>Token expiration during session:</b>
 * If the access token expires while the WebSocket is connected, the session
 * remains valid. The client should use its refresh token to get a new access
 * token via REST API and reconnect if needed. This is the standard approach
 * used by Discord, Slack, and WhatsApp.
 *
 * @see StompPrincipal
 * @see com.vivumate.coreapi.security.JwtUtils
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "WEBSOCKET_AUTH_INTERCEPTOR")
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_HEADER = "Authorization";
    private static final String SESSION_ATTR_USER_ID = "userId";
    private static final String SESSION_ATTR_USERNAME = "username";

    private final JwtUtils jwtUtils;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * Intercepts inbound STOMP messages before they reach the broker/controller.
     * Only processes CONNECT frames — all other frames pass through.
     *
     * @param message the inbound STOMP message
     * @param channel the message channel
     * @return the original message (if authenticated) — never returns null
     * @throws AppException if authentication fails (UNAUTHENTICATED or TOKEN_REVOKED)
     */
    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String sessionId = accessor.getSessionId();
        log.debug("(Attempt) STOMP CONNECT authentication. sessionId={}", sessionId);

        // 1. Extract Bearer token from STOMP CONNECT headers
        String token = extractTokenFromHeaders(accessor);
        if (token == null) {
            log.warn("(Failed) No Bearer token in STOMP CONNECT. sessionId={}", sessionId);
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }

        // 2. Check token blacklist (revoked tokens)
        if (tokenBlacklistService.isBlacklisted(token)) {
            log.warn("(Failed) Blacklisted token used for STOMP CONNECT. sessionId={}", sessionId);
            throw new AppException(ErrorCode.TOKEN_REVOKED);
        }

        // 3. Parse and validate JWT claims
        Claims claims;
        try {
            claims = jwtUtils.extractAllClaims(token, TokenType.ACCESS_TOKEN);
        } catch (JwtException e) {
            log.warn("(Failed) Invalid JWT in STOMP CONNECT. sessionId={}, reason={}", sessionId, e.getMessage());
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        // 4. Extract user identity from claims
        Long userId = claims.get("userId", Long.class);
        String username = claims.getSubject();

        if (userId == null || username == null) {
            log.warn("(Failed) JWT missing required claims (userId/username). sessionId={}", sessionId);
            throw new AppException(ErrorCode.TOKEN_INVALID);
        }

        // 5. Set authenticated user on the STOMP session
        StompPrincipal principal = new StompPrincipal(userId, username);
        accessor.setUser(principal);

        // 6. Store user info in session attributes for access in event listeners
        Map<String, Object> sessionAttrs = accessor.getSessionAttributes();
        if (sessionAttrs != null) {
            sessionAttrs.put(SESSION_ATTR_USER_ID, userId);
            sessionAttrs.put(SESSION_ATTR_USERNAME, username);
        }

        log.info("(Success) STOMP CONNECT authenticated. userId={}, username={}, sessionId={}",
                userId, username, sessionId);

        return message;
    }

    /**
     * Extracts the JWT token from the STOMP CONNECT frame headers.
     * <p>
     * The client sends the token via native STOMP headers:
     * <pre>
     * CONNECT
     * Authorization:Bearer eyJhbGciOiJIUzI1NiJ9...
     * </pre>
     *
     * @param accessor the STOMP header accessor
     * @return the raw JWT token string, or null if not present
     */
    private String extractTokenFromHeaders(StompHeaderAccessor accessor) {
        // STOMP native headers are sent as a Map<String, List<String>>
        List<String> authHeaders = accessor.getNativeHeader(AUTH_HEADER);

        if (authHeaders == null || authHeaders.isEmpty()) {
            return null;
        }

        String authValue = authHeaders.getFirst();
        if (authValue == null || !authValue.startsWith(BEARER_PREFIX)) {
            return null;
        }

        return authValue.substring(BEARER_PREFIX.length());
    }
}
