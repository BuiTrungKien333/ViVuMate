package com.vivumate.coreapi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for creating a Direct Message conversation.
 * <p>
 * DM creation is <b>idempotent</b>: if a conversation already exists
 * between the current user and the target user, it is returned instead
 * of creating a duplicate.
 * <p>
 * Example:
 * <pre>{@code
 * POST /api/v1/conversations/direct
 * { "otherUserId": 42 }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateDirectMessageRequest {

    /** PostgreSQL user ID of the other participant. */
    private Long otherUserId;
}
