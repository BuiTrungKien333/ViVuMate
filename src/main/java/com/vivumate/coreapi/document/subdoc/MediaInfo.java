package com.vivumate.coreapi.document.subdoc;

import lombok.*;

/**
 * Attachment/media metadata embedded in {@link MessageContent}.
 * The actual file is stored on CDN (S3/Cloudinary); only the URL is kept here.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaInfo {

    /** CDN URL of the original media file. */
    private String url;

    /** CDN URL of the thumbnail (for images/videos). */
    private String thumbnailUrl;

    /** Original filename as uploaded by the user. */
    private String filename;

    /** MIME type, e.g. "image/jpeg", "application/pdf". */
    private String mimeType;

    /** File size in bytes. */
    private Long sizeBytes;

    /** Image/video width in pixels (null for non-visual media). */
    private Integer width;

    /** Image/video height in pixels (null for non-visual media). */
    private Integer height;
}
