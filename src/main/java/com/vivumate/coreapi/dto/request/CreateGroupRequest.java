package com.vivumate.coreapi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request body for creating a Group conversation.
 * <p>
 * The creator is automatically added as an ADMIN participant.
 * Validation rules:
 * <ul>
 *   <li>Min 3 unique members (including creator)</li>
 *   <li>Max 100 members</li>
 * </ul>
 * <p>
 * Example:
 * <pre>{@code
 * POST /api/v1/conversations/group
 * {
 *   "name": "Project Team",
 *   "avatarUrl": "https://example.com/group.png",
 *   "memberIds": [2, 3, 4, 5]
 * }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateGroupRequest {

    /** Group display name. */
    private String name;

    /** Optional group avatar URL. */
    private String avatarUrl;

    /** List of PostgreSQL user IDs to add (creator is auto-included). */
    private List<Long> memberIds;
}
