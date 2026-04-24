package com.vivumate.coreapi.websocket.security;

import lombok.Getter;

import java.security.Principal;

/**
 * Custom Principal implementation for STOMP WebSocket sessions.
 * <p>
 * Encapsulates the authenticated user's identity (userId + username)
 * extracted from the JWT during the STOMP CONNECT handshake.
 * Spring uses {@link #getName()} to resolve the user destination
 * in {@code convertAndSendToUser()} calls.
 * <p>
 * <b>Design decision:</b> {@link #getName()} returns {@code userId} (as String)
 * rather than {@code username} because:
 * <ul>
 *   <li>userId is immutable — username may change in the future</li>
 *   <li>userId is used as the routing key in Redis ({@code ws_routing:{userId}})</li>
 *   <li>All cross-service communication references users by userId</li>
 * </ul>
 */
@Getter
public class StompPrincipal implements Principal {

    private final Long userId;
    private final String username;

    public StompPrincipal(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    /**
     * Returns userId as String — used by Spring STOMP as the user destination key.
     * <p>
     * Example: {@code messagingTemplate.convertAndSendToUser("1001", "/queue/messages", payload)}
     * → delivers to all sessions of user with id=1001
     */
    @Override
    public String getName() {
        return userId.toString();
    }
}
