package com.vivumate.coreapi.document.subdoc;

import com.vivumate.coreapi.document.enums.ContentType;
import lombok.*;
import org.bson.types.ObjectId;

/**
 * Lightweight preview of the message being replied to.
 * Embedded in the Message document to avoid $lookup when rendering reply chains.
 * <p>
 * Only stores minimal preview data; the full original message
 * can be fetched via {@code messageId} when needed.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReplyToSnapshot {

    private ObjectId messageId;

    private String senderName;

    /** Truncated content of the original message. */
    private String contentPreview;

    private ContentType contentType;
}
