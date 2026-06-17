package com.vivumate.coreapi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Request body for updating group conversation info (name and/or avatar).
 * <p>
 * At least one field must be non-null. Both can be updated at once.
 * Permission depends on group settings: if {@code onlyAdminsCanEditInfo}
 * is true, only ADMIN participants can update.
 * <p>
 * Example:
 * <pre>{@code
 * PUT /api/v1/conversations/{id}
 * { "name": "New Group Name", "avatarUrl": "https://example.com/new.png" }
 * }</pre>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateGroupInfoRequest {

    /** New group name. Null to keep current. */
    private String name;

    /** New group avatar URL. Null to keep current. */
    private String avatarUrl;
}
