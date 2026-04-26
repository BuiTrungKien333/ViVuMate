package com.vivumate.coreapi.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * Error response DTO for WebSocket STOMP error frames.
 * <p>
 * Follows the same structure as {@code ApiResponse} (code + message)
 * but tailored for the WebSocket context with an additional timestamp.
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketErrorResponse {

    private final int code;
    private final String message;

    @Builder.Default
    private final Instant timestamp = Instant.now();

    public static WebSocketErrorResponse of(int code, String message) {
        return WebSocketErrorResponse.builder()
                .code(code)
                .message(message)
                .build();
    }
}
