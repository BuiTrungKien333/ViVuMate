package com.vivumate.coreapi.document.subdoc;

import lombok.*;

/**
 * Group-specific settings embedded in the Conversation document.
 * Only applicable when conversation type is GROUP.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationSettings {

    /** If true, only admins can send messages (announcement mode). */
    @Builder.Default
    private boolean onlyAdminsCanSend = false;

    /** If true, only admins can edit group name/avatar/description. */
    @Builder.Default
    private boolean onlyAdminsCanEditInfo = true;

    /** If true, new members need admin approval to join. */
    @Builder.Default
    private boolean joinApprovalRequired = false;
}
