package com.vivumate.coreapi.document.subdoc;

import com.vivumate.coreapi.document.enums.JoinMethod;
import com.vivumate.coreapi.document.enums.ParticipantRole;
import lombok.*;

import java.time.Instant;

/**
 * Embedded participant info within a Conversation document.
 * Combines Extended Reference (user snapshot) with group-specific metadata.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Participant {

    /** PostgreSQL user ID. */
    private Long userId;

    private String username;

    private String fullName;

    private String avatarUrl;

    /** Only meaningful for GROUP conversations. */
    private ParticipantRole role;

    private Instant joinedAt;

    /** Optional nickname within this specific group. */
    private String nickname;

    /** Whether notifications are muted for this participant. */
    @Builder.Default
    private boolean muted = false;

    /** If muted temporarily, the mute expiry time. */
    private Instant mutedUntil;

    /**
     * Timestamp when this user clicked "Delete history".
     * Only query messages where created_at > clearedAt.
     */
    private Instant clearedAt;

    private Long addedByUserId;

    private String addedByFullName;

    private JoinMethod joinMethod;

}
