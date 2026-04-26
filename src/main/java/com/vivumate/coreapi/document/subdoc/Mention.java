package com.vivumate.coreapi.document.subdoc;

import com.vivumate.coreapi.document.enums.MentionType;
import lombok.*;

/**
 * Mention entry embedded in a Message document.
 * Supports both specific @user mentions and @everyone broadcasts.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Mention {

    /** PostgreSQL user ID. Null when type is EVERYONE. */
    private Long userId;

    /** Username for display. Null when type is EVERYONE. */
    private String fullName;

    private MentionType type;
}
