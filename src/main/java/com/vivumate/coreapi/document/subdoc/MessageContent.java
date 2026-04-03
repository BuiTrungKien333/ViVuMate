package com.vivumate.coreapi.document.subdoc;

import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.enums.SystemEvent;
import lombok.*;

/**
 * Polymorphic Pattern — message content varies by {@link ContentType}.
 * <p>
 * Field usage per content type:
 * <ul>
 *   <li><b>TEXT</b>: {@code text} only</li>
 *   <li><b>IMAGE/FILE/VIDEO/AUDIO</b>: {@code text} (caption) + {@code media}</li>
 *   <li><b>LINK_PREVIEW</b>: {@code text} + {@code linkPreview}</li>
 *   <li><b>SYSTEM</b>: {@code text} + {@code systemEvent} + {@code actorId} + {@code targetId}</li>
 * </ul>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MessageContent {

    /** Text body or caption. Present in all content types. */
    private String text;

    /** Media/attachment info — for IMAGE, FILE, VIDEO, AUDIO types. */
    private MediaInfo media;

    /** Open Graph metadata — for LINK_PREVIEW type. */
    private LinkPreview linkPreview;

    /** System event type — for SYSTEM type. */
    private SystemEvent systemEvent;

    /** User who triggered the system event. */
    private Long actorId;

    /** Target user of the system event (e.g. removed member). */
    private Long targetId;
}
