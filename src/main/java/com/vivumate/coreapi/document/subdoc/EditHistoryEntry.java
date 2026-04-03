package com.vivumate.coreapi.document.subdoc;

import lombok.*;

import java.time.Instant;

/**
 * Edit history entry — stores the previous content before an edit.
 * Embedded as a bounded array in the Message document.
 * <p>
 * Practical limit: a message is rarely edited more than 50 times,
 * so unbounded growth is not a concern.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EditHistoryEntry {

    /** The message content before this edit was applied. */
    private String previousContent;

    /** When this edit occurred. */
    private Instant editedAt;
}
