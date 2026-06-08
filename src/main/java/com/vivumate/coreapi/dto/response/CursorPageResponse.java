package com.vivumate.coreapi.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

/**
 * Cursor-based pagination response for infinite scroll / "load more" patterns.
 * <p>
 * Unlike offset-based pagination ({@link PageResponse}), cursor pagination:
 * <ul>
 *   <li>Has O(1) performance regardless of page depth (no SKIP cost)</li>
 *   <li>Is immune to insert/delete shifts between pages</li>
 *   <li>Is ideal for real-time data feeds (messages, conversations)</li>
 * </ul>
 *
 * <b>Client usage:</b>
 * <pre>{@code
 * // First page: GET /conversations/{id}/messages?limit=30
 * // Next pages: GET /conversations/{id}/messages?cursor={nextCursor}&limit=30
 * // When hasMore=false → stop requesting
 * }</pre>
 *
 * @param <T> the type of items in the response
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CursorPageResponse<T> {

    /**
     * List of items for the current page.
     */
    private List<T> items;

    /**
     * Cursor value to pass in the next request to get the next page.
     * Null when there are no more pages.
     */
    private String nextCursor;

    /**
     * Whether there are more items beyond this page.
     */
    private boolean hasMore;

    /**
     * Number of items in this page (convenience field).
     */
    private int size;
}
