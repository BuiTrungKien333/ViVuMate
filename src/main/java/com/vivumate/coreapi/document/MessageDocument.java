package com.vivumate.coreapi.document;

import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.subdoc.*;
import lombok.*;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * MongoDB document representing a single chat message.
 * <p>
 * <b>Patterns applied:</b>
 * <ul>
 *   <li><b>Polymorphic Pattern</b> — {@code contentType} + {@code content} sub-document
 *       adapts shape for TEXT, IMAGE, FILE, VIDEO, AUDIO, LINK_PREVIEW, SYSTEM</li>
 *   <li><b>Extended Reference</b> — {@code sender} embeds a lightweight user snapshot</li>
 * </ul>
 *
 * <b>Indexes:</b>
 * <ol>
 *   <li>{@code {conversation_id: 1, _id: -1}} — hot path: cursor-based message pagination</li>
 *   <li>{@code {thread_root_id: 1, _id: 1}} — partial: load thread replies</li>
 *   <li>{@code {content.text: "text"}} — full-text search on message content</li>
 *   <li>{@code {conversation_id: 1, mentions.user_id: 1}} — partial: find mentions</li>
 *   <li>{@code {sender.user_id: 1, created_at: -1}} — user's sent messages</li>
 * </ol>
 *
 * <b>Shard key recommendation:</b> {@code {conversation_id: "hashed"}} to co-locate
 * all messages of a conversation on the same shard.
 *
 * @see MessageContent
 * @see SenderSnapshot
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "messages")
public class MessageDocument extends BaseDocument {

    /** Reference to the parent conversation. */
    private ObjectId conversationId;

    // ═══════════════════════════════════════════════════════════
    //  SENDER — (Extended Reference Pattern)
    // ═══════════════════════════════════════════════════════════

    /**
     * Denormalized sender snapshot. Avoids $lookup on the hot message-feed path.
     * Updated via async background job when user changes profile (eventually consistent).
     */
    private SenderSnapshot sender;

    // ═══════════════════════════════════════════════════════════
    //  CONTENT — (Polymorphic Pattern)
    // ═══════════════════════════════════════════════════════════

    /** Discriminator field for the polymorphic content shape. */
    private ContentType contentType;

    /** Polymorphic content — shape varies based on {@code contentType}. */
    private MessageContent content;

    // ═══════════════════════════════════════════════════════════
    //  THREADING / REPLIES
    // ═══════════════════════════════════════════════════════════

    /** Preview of the message this is a direct reply to. Null if not a reply. */
    private ReplyToSnapshot replyTo;

    // ═══════════════════════════════════════════════════════════
    //  MENTIONS
    // ═══════════════════════════════════════════════════════════

    /** List of @mentions found in the message text. */
    @Builder.Default
    private List<Mention> mentions = new ArrayList<>();

    // ═══════════════════════════════════════════════════════════
    //  EDIT HISTORY
    // ═══════════════════════════════════════════════════════════

    /** Whether this message has been edited at least once. */
    @Builder.Default
    @Field("is_edited")
    private boolean edited = false;

    /**
     * Snapshot of the message content right before the most recent edit.
     * Null if the message has never been edited.
     */
    private EditHistoryEntry previousEdit;

    // ═══════════════════════════════════════════════════════════
    //  SOFT DELETE
    // ═══════════════════════════════════════════════════════════

    /**
     * PostgreSQL user IDs who have "deleted for me" this message.
     * Filtered client-side: {@code deleted_for: { $ne: currentUserId }}.
     */
    @Builder.Default
    private List<Long> deletedFor = new ArrayList<>();

    /** If true, message is hidden for all participants ("delete for everyone"). */
    @Builder.Default
    private boolean deletedForEveryone = false;
}
