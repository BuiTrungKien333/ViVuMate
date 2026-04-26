package com.vivumate.coreapi.service.impl;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.enums.MentionType;
import com.vivumate.coreapi.document.subdoc.*;
import com.vivumate.coreapi.dto.response.UserMiniResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.repository.mongodb.MessageRepository;
import com.vivumate.coreapi.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Business logic for message lifecycle (send, load, edit, recall).
 * <p>
 * Key architectural rules enforced here:
 * <ul>
 *   <li><b>Subset Pattern</b>: Every send/edit/recall that affects the last message
 *       MUST update {@code lastMessage} on the conversation</li>
 *   <li><b>Watermark Pattern</b>: Load/search always respect the user's {@code clearedAt}</li>
 *   <li><b>TOCTOU-free</b>: Ownership checks in repository predicates,
 *       service only reads {@code modifiedCount}</li>
 * </ul>
 */
@Service
@Slf4j(topic = "MESSAGE_SERVICE")
@RequiredArgsConstructor
public class MessageServiceImpl implements MessageService {

    private static final int CONTENT_PREVIEW_MAX_LENGTH = 100;

    private final MessageRepository messageRepository;
    private final ConversationRepository conversationRepository;
    private final UserRepository userRepository;

    // ═══════════════════════════════════════════════════════════
    //  SEND MESSAGE
    // ═══════════════════════════════════════════════════════════

    @Override
    public MessageDocument sendMessage(ObjectId conversationId, Long senderUserId,
                                        ContentType contentType, MessageContent content,
                                        List<Mention> mentions, ReplyToSnapshot replyTo) {
        // 1. Validate the sender is a participant
        ConversationDocument conversation = conversationRepository.findByIdAndParticipantId(conversationId, senderUserId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_ACCESS_DENIED));

        // 2. Build sender snapshot from PostgreSQL
        /*
        Nếu như tin nhắn này thuộc GROUP thì SenderSnapshot mới cần đủ thông tin (userId, username, full_name, avatar_url)
        Còn nếu là tin nhắn DIRECT thì lấy trực tiếp tại conversation đó luôn (lấy userId để biết ai gửi là được rồi)
         */
        SenderSnapshot sender = buildSenderSnapshot(senderUserId);

        // 3. Persist the message
        MessageDocument message = MessageDocument.builder()
                .conversationId(conversationId)
                .sender(sender)
                .contentType(contentType)
                .content(content)
                .mentions(mentions != null ? mentions : Collections.emptyList())
                .replyTo(replyTo)
                .build();

        MessageDocument saved = messageRepository.save(message);

        // 4. Update lastMessage preview (Subset Pattern)
        LastMessagePreview preview = buildLastMessagePreview(saved, sender);
        conversationRepository.updateLastMessage(conversationId, preview);

        // 5. Increment unread counts for all participants except sender
        List<Long> recipientIds = conversation.getParticipantIds().stream()
                .filter(id -> !id.equals(senderUserId))
                .toList();
        conversationRepository.incrementUnreadCounts(conversationId, recipientIds);

        // 6. Increment unread mention counts (if applicable)
        List<Long> mentionedUserIds = extractMentionedUserIds(mentions, conversation.getParticipantIds(), senderUserId);
        if (!mentionedUserIds.isEmpty()) {
            conversationRepository.incrementUnreadMentionsCounts(conversationId, mentionedUserIds);
        }

        log.info("Message sent: id={}, conversationId={}, sender={}, type={}",
                saved.getId(), conversationId, senderUserId, contentType);

        // TODO: Publish event for real-time delivery (WebSocket/Push Notification)
        // eventPublisher.publishEvent(new MessageSentEvent(saved, recipientIds));

        return saved;
    }

    // ═══════════════════════════════════════════════════════════
    //  LOAD MESSAGES
    // ═══════════════════════════════════════════════════════════

    @Override
    public List<MessageDocument> loadMessages(ObjectId conversationId, Long currentUserId,
                                               ObjectId cursor, int pageSize) {
        // 1. Get the user's clearedAt watermark
        Instant clearedAt = getClearedAt(conversationId, currentUserId);

        // 2. Delegate to repository with clearedAt as lower bound
        return messageRepository.findMessagesByConversation(
                conversationId, currentUserId, cursor, clearedAt, pageSize
        );
    }

    @Override
    public List<MessageDocument> searchMessages(ObjectId conversationId, Long currentUserId,
                                                 String keyword, int pageSize) {
        Instant clearedAt = getClearedAt(conversationId, currentUserId);
        return messageRepository.searchMessages(conversationId, currentUserId, clearedAt, keyword, pageSize);
    }

    // ═══════════════════════════════════════════════════════════
    //  EDIT MESSAGE
    // ═══════════════════════════════════════════════════════════

    @Override
    public void editMessage(ObjectId conversationId, ObjectId messageId,
                            Long senderUserId, MessageContent newContent) {
        // 1. Fetch the current message to build edit history
        MessageDocument originalMessage = messageRepository.findActiveByIdAndUserId(messageId, senderUserId)
                .orElseThrow(() -> new AppException(ErrorCode.MESSAGE_NOT_FOUND));

        // 2. Build edit history entry from the OLD content
        EditHistoryEntry historyEntry = EditHistoryEntry.builder()
                .previousContent(originalMessage.getContent().getText())
                .editedAt(Instant.now())
                .build();

        // 3. Atomic edit with ownership check in query predicate
        long modified = messageRepository.editMessage(messageId, senderUserId, newContent, historyEntry)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.MESSAGE_EDIT_DENIED);
        }

        // 4. If this message is the conversation's lastMessage, update the preview
        updateLastMessageIfNeeded(conversationId, messageId, senderUserId, newContent);

        log.info("Message edited: id={}, conversationId={}, by={}", messageId, conversationId, senderUserId);
    }

    // ═══════════════════════════════════════════════════════════
    //  RECALL MESSAGE (Delete for Everyone)
    // ═══════════════════════════════════════════════════════════

    @Override
    public void recallMessage(ObjectId conversationId, ObjectId messageId, Long senderUserId) {
        // 1. Atomic recall with ownership check
        long modified = messageRepository.deleteForEveryone(messageId, senderUserId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.MESSAGE_RECALL_DENIED);
        }

        // 2. If the recalled message IS the lastMessage, replace with penultimate
        replaceLastMessageIfNeeded(conversationId, messageId);

        log.info("Message recalled: id={}, conversationId={}, by={}", messageId, conversationId, senderUserId);
    }

    // ═══════════════════════════════════════════════════════════
    //  DELETE FOR ME
    // ═══════════════════════════════════════════════════════════

    @Override
    public void deleteForMe(ObjectId messageId, Long userId) {
        long modified = messageRepository.deleteForUser(messageId, userId)
                .getModifiedCount();

        if (modified == 0) {
            throw new AppException(ErrorCode.MESSAGE_NOT_FOUND);
        }

        log.debug("Message deleted for user: messageId={}, userId={}", messageId, userId);
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS — Snapshots & Previews
    // ═══════════════════════════════════════════════════════════

    /**
     * Build a sender snapshot from PostgreSQL user data.
     */
    private SenderSnapshot buildSenderSnapshot(Long userId) {
        List<UserMiniResponse> users = userRepository.findChatMembersByIds(List.of(userId));
        if (users.isEmpty()) {
            throw new AppException(ErrorCode.USER_NOT_FOUND);
        }
        UserMiniResponse user = users.getFirst();
        return SenderSnapshot.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    /**
     * Build a last message preview from a saved message.
     * Truncates content preview to 100 characters.
     */
    private LastMessagePreview buildLastMessagePreview(MessageDocument message, SenderSnapshot sender) {
        String contentPreview = buildContentPreview(message.getContentType(), message.getContent());

        return LastMessagePreview.builder()
                .messageId(message.getId())
                .senderId(sender.getUserId())
                .senderName(sender.getFullName())
                .contentPreview(contentPreview)
                .contentType(message.getContentType())
                .sentAt(message.getCreatedAt())
                .build();
    }

    /**
     * Generate a human-readable content preview string based on content type.
     * For non-text types, returns a label like "📷 Photo", "📎 File", etc.
     */
    private String buildContentPreview(ContentType contentType, MessageContent content) {
        if (content == null) return "";

        return switch (contentType) {
            case TEXT -> truncate(content.getText());
            case IMAGE -> "📷 " + (content.getText() != null ? truncate(content.getText()) : "Photo");
            case VIDEO -> "🎬 " + (content.getText() != null ? truncate(content.getText()) : "Video");
            case AUDIO -> "🎵 Audio";
            case FILE -> "📎 " + (content.getMedia() != null ? content.getMedia().getFilename() : "File");
            case LINK_PREVIEW -> "🔗 " + truncate(content.getText());
            case SYSTEM -> truncate(content.getText());
        };
    }

    private String truncate(String text) {
        if (text == null) return "";
        return text.length() > CONTENT_PREVIEW_MAX_LENGTH
                ? text.substring(0, CONTENT_PREVIEW_MAX_LENGTH)
                : text;
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS — Subset Pattern (lastMessage sync)
    // ═══════════════════════════════════════════════════════════

    /**
     * If the edited message IS the conversation's lastMessage, update the preview.
     * Otherwise, do nothing (non-last messages don't affect conversation list).
     */
    private void updateLastMessageIfNeeded(ObjectId conversationId, ObjectId messageId,
                                            Long senderUserId, MessageContent newContent) {
        ConversationDocument conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        LastMessagePreview lastMsg = conversation.getLastMessage();
        if (lastMsg != null && lastMsg.getMessageId() != null && lastMsg.getMessageId().equals(messageId)) {
            // The edited message IS the last message — rebuild the preview
            ContentType existingType = lastMsg.getContentType();
            String newPreview = buildContentPreview(existingType, newContent);

            LastMessagePreview updated = LastMessagePreview.builder()
                    .messageId(messageId)
                    .senderId(lastMsg.getSenderId())
                    .senderName(lastMsg.getSenderName())
                    .contentPreview(newPreview)
                    .contentType(existingType)
                    .sentAt(lastMsg.getSentAt())
                    .build();

            conversationRepository.updateLastMessage(conversationId, updated);
            log.debug("LastMessage preview updated after edit: conversationId={}, messageId={}", conversationId, messageId);
        }
    }

    /**
     * If the recalled message IS the lastMessage, find the penultimate
     * (next most recent valid message) and set it as the new lastMessage.
     */
    private void replaceLastMessageIfNeeded(ObjectId conversationId, ObjectId recalledMessageId) {
        ConversationDocument conversation = conversationRepository.findById(conversationId).orElse(null);
        if (conversation == null) return;

        LastMessagePreview lastMsg = conversation.getLastMessage();
        if (lastMsg == null || !recalledMessageId.equals(lastMsg.getMessageId())) {
            return; // Recalled message wasn't the last — no action needed
        }

        // Find the penultimate message
        messageRepository.findFirstByConversationIdAndDeletedForEveryoneIsFalseOrderByIdDesc(conversationId)
                .ifPresentOrElse(
                        penultimate -> {
                            SenderSnapshot sender = penultimate.getSender();
                            LastMessagePreview newPreview = buildLastMessagePreview(penultimate, sender);
                            conversationRepository.updateLastMessage(conversationId, newPreview);
                            log.debug("LastMessage replaced with penultimate: conversationId={}", conversationId);
                        },
                        () -> {
                            // No valid messages left — clear the preview
                            conversationRepository.updateLastMessage(conversationId,
                                    LastMessagePreview.builder()
                                            .contentPreview("")
                                            .contentType(ContentType.SYSTEM)
                                            .sentAt(Instant.now())
                                            .build());
                            log.debug("No messages left, lastMessage cleared: conversationId={}", conversationId);
                        }
                );
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS — Mentions
    // ═══════════════════════════════════════════════════════════

    /**
     * Extract user IDs that should have their unread mention count incremented.
     * Handles EVERYONE mentions (all participants except sender).
     */
    private List<Long> extractMentionedUserIds(List<Mention> mentions, List<Long> participantIds, Long senderUserId) {
        if (mentions == null || mentions.isEmpty()) {
            return Collections.emptyList();
        }

        boolean hasEveryoneMention = mentions.stream()
                .anyMatch(m -> m.getType() == MentionType.EVERYONE);

        if (hasEveryoneMention) {
            // @everyone: all participants except sender
            return participantIds.stream()
                    .filter(id -> !id.equals(senderUserId))
                    .toList();
        }

        // Individual @user mentions (excluding sender)
        return mentions.stream()
                .filter(m -> m.getType() == MentionType.USER && m.getUserId() != null)
                .map(Mention::getUserId)
                .filter(id -> !id.equals(senderUserId))
                .distinct()
                .toList();
    }

    // ═══════════════════════════════════════════════════════════
    //  PRIVATE HELPERS — Watermark
    // ═══════════════════════════════════════════════════════════

    /**
     * Get the clearedAt timestamp for the current user from the conversation's participant list.
     * Returns null if the user has never cleared history.
     */
    private Instant getClearedAt(ObjectId conversationId, Long userId) {
        ConversationDocument conversation = conversationRepository.findByIdAndParticipantId(conversationId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.CONVERSATION_ACCESS_DENIED));

        return conversation.getParticipants().stream()
                .filter(p -> p.getUserId().equals(userId))
                .findFirst()
                .map(Participant::getClearedAt)
                .orElse(null);
    }
}
