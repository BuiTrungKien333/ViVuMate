package com.vivumate.coreapi.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Request body for adding or removing members from a group conversation.
 * <p>
 * Used by both:
 * <ul>
 *   <li>{@code POST /conversations/{id}/members} — add members</li>
 *   <li>{@code DELETE /conversations/{id}/members} — remove members (request body)</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MemberActionRequest {

    /** List of PostgreSQL user IDs to add or remove. */
    private List<Long> memberIds;
}
