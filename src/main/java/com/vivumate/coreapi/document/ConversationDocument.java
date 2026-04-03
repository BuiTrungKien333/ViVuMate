package com.vivumate.coreapi.document;

import com.vivumate.coreapi.document.enums.ConversationType;
import com.vivumate.coreapi.document.subdoc.ConversationSettings;
import com.vivumate.coreapi.document.subdoc.LastMessagePreview;
import com.vivumate.coreapi.document.subdoc.Participant;
import lombok.*;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MongoDB document representing a conversation (DM or Group).
 * <p>
 * <b>Patterns applied:</b>
 * <ul>
 * <li><b>Computed Pattern</b> — {@code memberCount}, {@code unreadCounts}
 * pre-aggregated</li>
 * <li><b>Subset Pattern</b> — {@code lastMessage} embeds only preview
 * fields</li>
 * <li><b>Extended Reference</b> — {@code participants} embeds lightweight user
 * snapshots</li>
 * </ul>
 *
 * <b>Indexes:</b>
 * <ol>
 * <li>{@code {participant_ids: 1, last_activity_at: -1}} — hot path: user's
 * conversation list</li>
 * <li>{@code {dm_hash: 1}} — unique sparse: prevent duplicate DMs</li>
 * <li>{@code {type: 1, last_activity_at: -1}} — filter by conversation
 * type</li>
 * </ol>
 *
 * @see Participant
 * @see LastMessagePreview
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "conversations")
public class ConversationDocument extends BaseDocument {

    /** DIRECT (1-1) or GROUP (multi-participant). */
    private ConversationType type;

    /** Group name. Null for DIRECT conversations. */
    private String name;

    /** Group avatar URL. Null for DIRECT conversations. */
    private String avatarUrl;

    // ═══════════════════════════════════════════════════════════
    // PARTICIPANTS — (Extended Reference Pattern, embedded)
    // ═══════════════════════════════════════════════════════════

    /**
     * Full participant details with user snapshots.
     * Bounded: group limit ~500 members
     */
    @Builder.Default
    private List<Participant> participants = new ArrayList<>();

    /**
     * Flat array of PostgreSQL user IDs for efficient querying.
     * Indexed for {@code {participant_ids: userId}} queries.
     */
    @Builder.Default
    private List<Long> participantIds = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════
    // COMPUTED FIELDS — (Computed Pattern)
    // ═══════════════════════════════════════════════════════════

    /** Pre-computed member count. Updated on add/remove member. */
    @Builder.Default
    private int memberCount = 0;

    /**
     * Per-user unread message count. Key = PostgreSQL userId (as String), Value =
     * count.
     * Updated via atomic {@code $inc} on new message, reset on mark-as-read.
     * <p>
     * Uses Map&lt;String, Integer&gt; because MongoDB document keys must be
     * strings.
     */
    @Builder.Default
    private Map<String, Integer> unreadCounts = new HashMap<>();

    // ═══════════════════════════════════════════════════════════
    // LAST MESSAGE PREVIEW — (Subset Pattern)
    // ═══════════════════════════════════════════════════════════

    /** Denormalized preview of the most recent message. */
    private LastMessagePreview lastMessage;

    /**
     * Timestamp of the last activity (= last_message.sent_at).
     * <b>Critical for sorting conversation list</b> — the hottest read path.
     */
    private Instant lastActivityAt;

    // ═══════════════════════════════════════════════════════════
    // GROUP SETTINGS
    // ═══════════════════════════════════════════════════════════

    /** Group-specific settings. Null for DIRECT conversations. */
    private ConversationSettings settings;

    // ═══════════════════════════════════════════════════════════
    // DIRECT MESSAGE — unique hash
    // ═══════════════════════════════════════════════════════════

    /**
     * Unique string format for DIRECT conversations: "smallerId_largerId" (e.g.,
     * "1001_1002").
     * Used with a unique sparse index to prevent duplicate DM creation.
     * Null for GROUP conversations.
     */
    @Indexed(unique = true, sparse = true, name = "idx_dm_hash_unique")
    private String dmHash;

    /** PostgreSQL user ID of the conversation creator. */
    private Long createdBy;
}
