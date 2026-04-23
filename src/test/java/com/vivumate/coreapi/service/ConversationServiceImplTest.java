package com.vivumate.coreapi.service;

import com.vivumate.coreapi.document.ConversationDocument;
import com.vivumate.coreapi.document.subdoc.Participant;
import com.vivumate.coreapi.exception.AppException;
import com.vivumate.coreapi.exception.ErrorCode;
import com.vivumate.coreapi.repository.mongodb.ConversationRepository;
import com.vivumate.coreapi.service.impl.ConversationServiceImpl;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;
import static org.mockito.BDDMockito.*;
import static org.mockito.Mockito.times;


@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationRepository conversationRepository;

    @InjectMocks
    private ConversationServiceImpl conversationService;

    @Captor
    private ArgumentCaptor<String> nicknameCaptor;
    @Captor
    private ArgumentCaptor<String> fallbackNameCaptor;

    @Test
    @DisplayName("Should successfully change nickname when user exists in conversation")
    void changeNickname_Success() {
        ObjectId convId = new ObjectId();
        Long targetUserId = 10L;
        String newNickname = "Anh Kiên ProMax";
        String originalFullName = "Bùi Trung Kiên";

        Participant mockParticipant = Participant.builder()
                .userId(targetUserId)
                .fullName(originalFullName)
                .build();

        ConversationDocument mockConversation = new ConversationDocument();
        mockConversation.setParticipants(List.of(mockParticipant));

        // (Teach the Mock what to do when called)
        given(conversationRepository.findByIdAndParticipantId(convId, targetUserId))
                .willReturn(Optional.of(mockConversation)); // khi gọi repository kia thì sẽ trả về cái giả (mock) này

        // ==========================================
        // WHEN
        // ==========================================
        conversationService.changeNickName(convId, targetUserId, newNickname);

        // ==========================================
        // THEN
        // ==========================================
        // (Verify the DB updateNickname was CALLED exactly 1 time)
        then(conversationRepository).should(times(1)).updateNickname(
                org.mockito.ArgumentMatchers.eq(convId),
                org.mockito.ArgumentMatchers.eq(targetUserId),
                nicknameCaptor.capture(),
                fallbackNameCaptor.capture()
        );

        // (Verify the data sent to DB was correctly normalized (trimmed) using AssertJ)
        assertThat(nicknameCaptor.getValue()).isEqualTo("Anh Kiên ProMax");
        assertThat(fallbackNameCaptor.getValue()).isEqualTo(originalFullName);
    }

    @Test
    @DisplayName("Should throw Exception when user is not found in conversation participants")
    void changeNickname_ThrowsException_WhenUserNotFound() {
        // ==========================================
        // GIVEN
        // ==========================================
        ObjectId convId = new ObjectId();
        Long targetUserId = 99L; // (This user is not in the group)

        // (This group only has user 10, no user 99)
        Participant mockParticipant = Participant.builder().userId(10L).build();
        ConversationDocument mockConversation = new ConversationDocument();
        mockConversation.setParticipants(List.of(mockParticipant));

        given(conversationRepository.findByIdAndParticipantId(convId, targetUserId))
                .willReturn(Optional.of(mockConversation));

        // ==========================================
        // (Combined because we are catching an Exception)
        // ==========================================
        assertThatThrownBy(() -> conversationService.changeNickName(convId, targetUserId, "New Name"))
                .isInstanceOf(AppException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.PARTICIPANT_NOT_FOUND);

        // (Verify the Database is NEVER CALLED because the logic was blocked early)
        then(conversationRepository).should(times(0)).updateNickname(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

}
