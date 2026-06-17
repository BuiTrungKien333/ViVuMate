package com.vivumate.coreapi.dto.request;

import com.vivumate.coreapi.document.subdoc.MessageContent;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for editing a message via REST API.
 * <p>
 * <b>Example payload:</b>
 * <pre>{@code
 * {
 *   "content": {
 *     "text": "Updated message content"
 *   }
 * }
 * }</pre>
 * <p>
 * Only the {@code content} field is updated — {@code contentType}, {@code sender},
 * {@code mentions}, etc. remain unchanged from the original message.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EditMessageRequest {

    /**
     * The new message content to replace the existing content.
     * Must contain at least a non-empty {@code text} field.
     */
    private MessageContent content;
}
