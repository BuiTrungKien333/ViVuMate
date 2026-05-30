package com.vivumate.coreapi.websocket.dto;

import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.subdoc.Mention;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import com.vivumate.coreapi.document.subdoc.ReplyToSnapshot;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * STOMP SEND payload from client for {@code /app/chat.send}.
 * <p>
 * Client must generate a UUID {@code clientMessageId} for idempotency:
 * <ul>
 *   <li>Prevents duplicate processing on reconnect/retry</li>
 *   <li>Allows ACK correlation (client matches ACK to pending message)</li>
 *   <li>Enables multi-device dedup (other devices ignore own message)</li>
 * </ul>
 *
 * <b>Example payload:</b>
 * <pre>{@code
 * {
 *   "clientMessageId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
 *   "conversationId": "6650a1b2c3d4e5f6a7b8c9d0",
 *   "contentType": "TEXT",
 *   "content": { "text": "Hello World!" },
 *   "mentions": [],
 *   "replyTo": null
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SendMessageRequest {

    /**
     * Client-generated UUID for idempotency and ACK correlation.
     * Must be unique per message attempt from the same user.
     */
    private String clientMessageId;

    /**
     * Target conversation's MongoDB ObjectId as hex string (24 chars).
     */
    private String conversationId;

    /**
     * Content type discriminator — determines the shape of {@code content}.
     */
    private ContentType contentType;

    /**
     * Polymorphic message content — shape varies by {@code contentType}.
     */
    private MessageContent content;

    /**
     * Optional list of @mentions in the message text.
     */
    private List<Mention> mentions;

    /**
     * Optional reply-to snapshot (if this message is a reply).
     */
    private ReplyToSnapshot replyTo;
}
