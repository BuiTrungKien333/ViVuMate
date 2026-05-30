package com.vivumate.coreapi.websocket.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.subdoc.Mention;
import com.vivumate.coreapi.document.subdoc.MessageContent;
import com.vivumate.coreapi.document.subdoc.ReplyToSnapshot;
import com.vivumate.coreapi.document.subdoc.SenderSnapshot;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.List;

/**
 * Broadcast payload sent to ALL conversation participants via {@code /user/queue/messages}.
 * <p>
 * <b>Multi-device behavior:</b>
 * <ul>
 *   <li><b>Sender's sending device:</b> receives both ACK (on /queue/ack) and this broadcast.
 *       Deduplicates via {@code clientMessageId} — if the message was rendered optimistically,
 *       simply update its status.</li>
 *   <li><b>Sender's other devices:</b> receive this broadcast and render the message
 *       (sync across phone/laptop/tablet).</li>
 *   <li><b>Recipients:</b> receive this broadcast, render message, and show notification.</li>
 * </ul>
 *
 * <b>Client-side dedup logic:</b>
 * <pre>{@code
 * onMessage(broadcast) {
 *   if (broadcast.sender.userId === myUserId
 *       && pendingMessages.has(broadcast.clientMessageId)) {
 *       // Own message, already rendered optimistically → skip
 *       pendingMessages.delete(broadcast.clientMessageId);
 *       return;
 *   }
 *   // New message (from other user, or own message on another device) → render
 *   renderMessage(broadcast);
 * }
 * }</pre>
 */
@Getter
@Builder
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MessageBroadcast {

    /** Server-generated message ID (MongoDB ObjectId hex). */
    private final String id;

    /**
     * Client-generated UUID — included in broadcast for multi-device deduplication.
     * The sending device uses this to match against its pending messages.
     */
    private final String clientMessageId;

    /** Conversation this message belongs to. */
    private final String conversationId;

    /** Sender info snapshot (userId, username, fullName, avatarUrl). */
    private final SenderSnapshot sender;

    /** Content type discriminator. */
    private final ContentType contentType;

    /** Polymorphic message content. */
    private final MessageContent content;

    /** @mentions in the message. */
    private final List<Mention> mentions;

    /** Reply-to snapshot (null if not a reply). */
    private final ReplyToSnapshot replyTo;

    /** Server-side creation timestamp (from MongoDB @CreatedDate). */
    private final Instant createdAt;

    /**
     * Factory method to convert a persisted {@link MessageDocument} to a broadcast payload.
     * <p>
     * Converts MongoDB ObjectIds to hex strings for JSON serialization.
     */
    public static MessageBroadcast from(MessageDocument doc) {
        return MessageBroadcast.builder()
                .id(doc.getId().toHexString())
                .clientMessageId(doc.getClientMessageId())
                .conversationId(doc.getConversationId().toHexString())
                .sender(doc.getSender())
                .contentType(doc.getContentType())
                .content(doc.getContent())
                .mentions(doc.getMentions())
                .replyTo(doc.getReplyTo())
                .createdAt(doc.getCreatedAt())
                .build();
    }
}
