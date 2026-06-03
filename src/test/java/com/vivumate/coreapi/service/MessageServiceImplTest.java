package com.vivumate.coreapi.service;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.MessageDocument;
import com.vivumate.coreapi.document.enums.ContentType;
import com.vivumate.coreapi.document.enums.ConversationType;
import com.vivumate.coreapi.document.subdoc.*;
import com.vivumate.coreapi.dto.response.UserMiniResponse;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.repository.UserRepository;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.repository.mongodb.MessageRepository;
import com.vivumate.coreapi.service.MessageService.SendMessageResult;
import com.vivumate.coreapi.service.impl.MessageServiceImpl;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private MessageServiceImpl messageService;

    @Captor
    private ArgumentCaptor<MessageDocument> messageCaptor;

    @Captor
    private ArgumentCaptor<LastMessagePreview> lastMessageCaptor;

    @Captor
    private ArgumentCaptor<List<Long>> recipientIdsCaptor;

    @Test
    @DisplayName("Should successfully send message in DIRECT chat (No PostgreSQL query, SenderSnapshot only has userId)")
    void sendMessage_Success_DirectChat() {
        // GIVEN
        ObjectId convId = new ObjectId();
        Long senderId = 1L;
        Long recipientId = 2L;
        String clientMsgId = "client-uuid-123";
        MessageContent textContent = MessageContent.builder().text("Xin chào Direct Chat!").build();

        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.DIRECT)
                .participantIds(List.of(senderId, recipientId))
                .participants(List.of(
                        Participant.builder().userId(senderId).fullName("Sender A").build(),
                        Participant.builder().userId(recipientId).fullName("Receiver B").build()
                ))
                .build();
        conversation.setId(convId);

        MessageDocument savedMessage = MessageDocument.builder()
                .conversationId(convId)
                .clientMessageId(clientMsgId)
                .contentType(ContentType.TEXT)
                .content(textContent)
                .build();
        savedMessage.setId(new ObjectId());
        savedMessage.setCreatedAt(Instant.now());

        given(conversationRepository.findByIdAndParticipantId(convId, senderId))
                .willReturn(Optional.of(conversation));
        given(messageRepository.save(any(MessageDocument.class)))
                .willReturn(savedMessage);

        // WHEN
        SendMessageResult result = messageService.sendMessage(
                convId, senderId, clientMsgId, ContentType.TEXT, textContent, Collections.emptyList(), null
        );

        // THEN
        // 1. Verify response result
        assertThat(result.savedMessage()).isEqualTo(savedMessage);
        assertThat(result.recipientIds()).containsExactly(recipientId);
        assertThat(result.allParticipantIds()).containsExactlyInAnyOrder(senderId, recipientId);

        // 2. Verify Message persist details
        then(messageRepository).should(times(1)).save(messageCaptor.capture());
        MessageDocument capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage.getClientMessageId()).isEqualTo(clientMsgId);
        assertThat(capturedMessage.getSender()).isNotNull();
        assertThat(capturedMessage.getSender().getUserId()).isEqualTo(senderId);
        assertThat(capturedMessage.getSender().getFullName()).isNull(); // DIRECT chat skips name/avatar persistence

        // 3. Verify PostgreSQL userRepository was NEVER called
        then(userRepository).shouldHaveNoInteractions();

        // 4. Verify side effects
        then(conversationRepository).should(times(1)).updateLastMessage(eq(convId), lastMessageCaptor.capture());
        assertThat(lastMessageCaptor.getValue().getContentPreview()).isEqualTo("Xin chào Direct Chat!");
        assertThat(lastMessageCaptor.getValue().getSenderId()).isEqualTo(senderId);

        then(conversationRepository).should(times(1)).incrementUnreadCounts(eq(convId), recipientIdsCaptor.capture());
        assertThat(recipientIdsCaptor.getValue()).containsExactly(recipientId);
    }

    @Test
    @DisplayName("Should successfully send message in GROUP chat using in-memory participants list (No PostgreSQL query)")
    void sendMessage_Success_GroupChat_FromParticipants() {
        // GIVEN
        ObjectId convId = new ObjectId();
        Long senderId = 1L;
        Long memberId = 2L;
        String clientMsgId = "group-uuid-123";
        MessageContent textContent = MessageContent.builder().text("Chào cả nhà!").build();

        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.GROUP)
                .participantIds(List.of(senderId, memberId))
                .participants(List.of(
                        Participant.builder()
                                .userId(senderId)
                                .username("sender_a")
                                .fullName("Sender A")
                                .nickname("Biệt danh A")
                                .avatarUrl("http://avatar.a")
                                .build(),
                        Participant.builder()
                                .userId(memberId)
                                .username("member_b")
                                .fullName("Member B")
                                .build()
                ))
                .build();
        conversation.setId(convId);

        MessageDocument savedMessage = MessageDocument.builder()
                .conversationId(convId)
                .clientMessageId(clientMsgId)
                .contentType(ContentType.TEXT)
                .content(textContent)
                .build();
        savedMessage.setId(new ObjectId());
        savedMessage.setCreatedAt(Instant.now());

        given(conversationRepository.findByIdAndParticipantId(convId, senderId))
                .willReturn(Optional.of(conversation));
        given(messageRepository.save(any(MessageDocument.class)))
                .willReturn(savedMessage);

        // WHEN
        SendMessageResult result = messageService.sendMessage(
                convId, senderId, clientMsgId, ContentType.TEXT, textContent, Collections.emptyList(), null
        );

        // THEN
        assertThat(result.savedMessage()).isEqualTo(savedMessage);

        then(messageRepository).should(times(1)).save(messageCaptor.capture());
        MessageDocument capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage.getSender()).isNotNull();
        assertThat(capturedMessage.getSender().getUserId()).isEqualTo(senderId);
        assertThat(capturedMessage.getSender().getUsername()).isEqualTo("sender_a");
        assertThat(capturedMessage.getSender().getFullName()).isEqualTo("Sender A");
        assertThat(capturedMessage.getSender().getNickname()).isEqualTo("Biệt danh A");
        assertThat(capturedMessage.getSender().getAvatarUrl()).isEqualTo("http://avatar.a");

        // Verify PostgreSQL userRepository was NEVER called
        then(userRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Should fallback to PostgreSQL query in GROUP chat when sender is missing in participants list")
    void sendMessage_Success_GroupChat_FallbackToPostgreSQL() {
        // GIVEN
        ObjectId convId = new ObjectId();
        Long senderId = 1L;
        Long memberId = 2L;
        String clientMsgId = "group-uuid-fallback";
        MessageContent textContent = MessageContent.builder().text("Tin nhắn mới tinh!").build();

        // Sender 1 is NOT present in participants list (e.g. newly joined, concurrent state lag)
        ConversationDocument conversation = ConversationDocument.builder()
                .type(ConversationType.GROUP)
                .participantIds(List.of(senderId, memberId))
                .participants(List.of(
                        Participant.builder().userId(memberId).username("member_b").fullName("Member B").build()
                ))
                .build();
        conversation.setId(convId);

        MessageDocument savedMessage = MessageDocument.builder()
                .conversationId(convId)
                .clientMessageId(clientMsgId)
                .contentType(ContentType.TEXT)
                .content(textContent)
                .build();
        savedMessage.setId(new ObjectId());
        savedMessage.setCreatedAt(Instant.now());

        UserMiniResponse pgUserResponse = UserMiniResponse.builder()
                .id(senderId)
                .username("pg_sender")
                .fullName("PG Sender Fullname")
                .avatarUrl("http://pg.avatar")
                .build();

        given(conversationRepository.findByIdAndParticipantId(convId, senderId))
                .willReturn(Optional.of(conversation));
        given(userRepository.findChatMembersByIds(List.of(senderId)))
                .willReturn(List.of(pgUserResponse));
        given(messageRepository.save(any(MessageDocument.class)))
                .willReturn(savedMessage);

        // WHEN
        SendMessageResult result = messageService.sendMessage(
                convId, senderId, clientMsgId, ContentType.TEXT, textContent, Collections.emptyList(), null
        );

        // THEN
        assertThat(result.savedMessage()).isEqualTo(savedMessage);

        then(messageRepository).should(times(1)).save(messageCaptor.capture());
        MessageDocument capturedMessage = messageCaptor.getValue();
        assertThat(capturedMessage.getSender()).isNotNull();
        assertThat(capturedMessage.getSender().getUserId()).isEqualTo(senderId);
        assertThat(capturedMessage.getSender().getUsername()).isEqualTo("pg_sender");
        assertThat(capturedMessage.getSender().getFullName()).isEqualTo("PG Sender Fullname");
        assertThat(capturedMessage.getSender().getAvatarUrl()).isEqualTo("http://pg.avatar");

        // Verify PostgreSQL userRepository was indeed CALLED due to missing participant in-memory
        then(userRepository).should(times(1)).findChatMembersByIds(List.of(senderId));
    }

    @Test
    @DisplayName("Should throw Exception when sender is not a participant in the conversation")
    void sendMessage_ThrowsException_WhenSenderAccessDenied() {
        // GIVEN
        ObjectId convId = new ObjectId();
        Long senderId = 99L; // malicious sender trying to inject messages into conversation

        given(conversationRepository.findByIdAndParticipantId(convId, senderId))
                .willReturn(Optional.empty()); // DB says "You do not belong to this chat"

        // WHEN & THEN
        assertThatThrownBy(() -> messageService.sendMessage(
                convId, senderId, "malicious-123", ContentType.TEXT,
                MessageContent.builder().text("Hacker here!").build(),
                Collections.emptyList(), null
        ))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.CONVERSATION_ACCESS_DENIED);

        // Verify no message was persisted, no side effects called
        then(messageRepository).shouldHaveNoInteractions();
        then(conversationRepository).should(times(0)).updateLastMessage(any(), any());
    }
}
