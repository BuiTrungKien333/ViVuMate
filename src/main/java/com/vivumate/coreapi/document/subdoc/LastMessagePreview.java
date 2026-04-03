package com.vivumate.coreapi.document.subdoc;

import com.vivumate.coreapi.document.enums.ContentType;
import lombok.*;
import org.bson.types.ObjectId;

import java.time.Instant;

/**
 * Subset Pattern — lightweight preview of the last message in a conversation.
 * Embedded in the Conversation document for fast conversation-list rendering.
 * <p>
 * Updated atomically each time a new message is sent to the conversation.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LastMessagePreview {

    private ObjectId messageId;

    /** PostgreSQL user ID of the sender. */
    private Long senderId;

    private String senderName;

    /** Truncated to 100 characters max. */
    private String contentPreview;

    private ContentType contentType;

    private Instant sentAt;
}
