package com.vivumate.coreapi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for leaving a group conversation.
 * <p>
 * If the leaving user is an ADMIN and the group still has other members,
 * they MUST provide {@code nextAdminId} to transfer ownership.
 * <p>
 * Example:
 * <pre>{@code
 * POST /api/v1/conversations/{id}/leave
 * { "nextAdminId": 5 }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class LeaveGroupRequest {

    /**
     * User ID to promote to ADMIN before leaving.
     * Required when the leaving user is an admin and group has other members.
     * Null if the user is not an admin.
     */
    private Long nextAdminId;
}
