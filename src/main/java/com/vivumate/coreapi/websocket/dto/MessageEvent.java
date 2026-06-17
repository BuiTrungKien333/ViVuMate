package com.vivumate.coreapi.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;

/**
 * WebSocket event broadcast to conversation participants when a message is edited or recalled.
 * <p>
 * Delivered via {@code /user/queue/events} to all online participants.
 * Offline participants will see the change on their next message history sync.
 * <p>
 * <b>Client handling:</b>
 * <pre>{@code
 * onEvent(event) {
 *   switch (event.type) {
 *     case "MESSAGE_EDITED":
 *       // Update message content in local state
 *       updateMessage(event.messageId, event.newContent, event.isEdited=true);
 *       break;
 *     case "MESSAGE_RECALLED":
 *       // Replace message with "This message was deleted" placeholder
 *       markMessageRecalled(event.messageId);
 *       break;
 *   }
 * }
 * }</pre>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageEvent {

    /**
     * Event type discriminator.
     */
    private final EventType type;

    /**
     * The affected message's ID (MongoDB ObjectId hex).
     */
    private final String messageId;

    /**
     * The conversation containing the affected message.
     */
    private final String conversationId;

    /**
     * The user who performed the action (edit/recall).
     */
    private final Long actorId;

    /**
     * Updated message content — present only for {@code MESSAGE_EDITED}.
     * Null for {@code MESSAGE_RECALLED}.
     */
    private final MessageContent newContent;

    /**
     * Server timestamp when the event was generated.
     */
    @Builder.Default
    private final Instant timestamp = Instant.now();

    /**
     * Event types for message lifecycle operations.
     * <p>
     * Kept separate from {@code ContentType} because these represent
     * <b>mutations on existing messages</b>, not new message types.
     */
    public enum EventType {
        /** Message content was updated by the sender. */
        MESSAGE_EDITED,
        /** Message was recalled (deleted for everyone) by the sender. */
        MESSAGE_RECALLED
    }
}
