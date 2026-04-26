package com.vivumate.coreapi.document.subdoc;

import lombok.*;

/**
 * Extended Reference Pattern — lightweight snapshot of a user.
 * Embedded inside messages (sender), conversations (participants), etc.
 * Source of truth remains in PostgreSQL; this is a denormalized copy for read performance.
 *
 * @see com.vivumate.coreapi.entity.User
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SenderSnapshot {

    /** PostgreSQL user ID (foreign key logic). */
    private Long userId;

    private String username;

    private String fullName;

    private String avatarUrl;
}
