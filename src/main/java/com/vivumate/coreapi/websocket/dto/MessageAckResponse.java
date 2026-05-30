package com.vivumate.coreapi.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * ACK response sent to the message sender via {@code /user/queue/ack}.
 * <p>
 * Allows the client to:
 * <ul>
 *   <li>Correlate ACK with the pending message using {@code clientMessageId}</li>
 *   <li>Update optimistic UI from SENDING → SENT</li>
 *   <li>Detect duplicates (status = DUPLICATE)</li>
 *   <li>Obtain the server-generated {@code messageId} for future operations</li>
 * </ul>
 *
 * <b>Client handling:</b>
 * <pre>{@code
 * if (ack.status === "SENT")      → mark message as delivered to server
 * if (ack.status === "DUPLICATE") → message was already processed, use messageId
 * if (no ACK within 10s)          → show retry button (FAILED state)
 * }</pre>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageAckResponse {

    /**
     * Client-generated UUID — correlation key to match this ACK with the pending message.
     */
    private final String clientMessageId;

    /**
     * Server-generated MongoDB ObjectId (hex string) — the message's permanent ID.
     */
    private final String messageId;

    /**
     * Processing result.
     */
    private final AckStatus status;

    /**
     * Server timestamp of when the ACK was generated.
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * ACK status indicating how the server processed the message.
     */
    public enum AckStatus {
        /** Message was successfully persisted and broadcast. */
        SENT,
        /** Message with this clientMessageId was already processed (idempotent). */
        DUPLICATE
    }
}
