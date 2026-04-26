package com.vivumate.coreapi.document.subdoc;

import lombok.*;

/**
 * Open Graph link preview metadata embedded in {@link MessageContent}.
 * Populated by a server-side link-preview service when a URL is detected in a message.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LinkPreview {

    /** The original URL that was previewed. */
    private String url;

    /** og:title */
    private String ogTitle;

    /** og:description */
    private String ogDescription;

    /** og:image URL */
    private String ogImage;

    /** og:site_name */
    private String ogSiteName;
}
