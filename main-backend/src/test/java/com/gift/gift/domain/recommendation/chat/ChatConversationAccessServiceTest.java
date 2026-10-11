package com.gift.gift.domain.recommendation.chat;

import java.util.UUID;

import org.junit.jupiter.api.*;

import com.gift.gift.domain.user.service.AuthenticatedUserService;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChatConversationAccessServiceTest {

    private static final UUID ID =
            UUID.fromString("df68e1e3-6c35-4cda-8250-1092cf78ea23");

    private final AuthenticatedUserService users =
            mock(AuthenticatedUserService.class);

    private final ChatConversationTokenProvider tokens =
            mock(ChatConversationTokenProvider.class);

    private final ChatConversationAccessService service =
            new ChatConversationAccessService(users, tokens);

    @Test
    @DisplayName("대화 준비는 활성 발신자와 수신자를 검증하며 토큰을 발급하지 않는다")
    void prepare_validatesParticipantsWithoutIssuingToken() {
        when(users.isActive(1L)).thenReturn(true);
        when(users.isActive(2L)).thenReturn(true);

        assertEquals(
                new ChatConversationIdentity(ID, 1L, 2L),
                service.prepare(1L, ID.toString(), 2L)
        );

        verifyNoInteractions(tokens);
    }

    @Test
    @DisplayName("로그인 ID가 없거나 비활성 발신자이면 401이다")
    void prepare_rejectsMissingOrInactiveSender() {
        assertCode(
                ErrorCode.UNAUTHORIZED,
                () -> service.prepare(null, ID.toString(), 2L)
        );

        assertCode(
                ErrorCode.UNAUTHORIZED,
                () -> service.prepare(1L, ID.toString(), 2L)
        );

        verifyNoInteractions(tokens);
    }

    @Test
    @DisplayName("없는·삭제된·비활성 수신자의 활성 조회 실패는 404이다")
    void prepare_rejectsUnavailableRecipient() {
        when(users.isActive(1L)).thenReturn(true);
        when(users.isActive(2L)).thenReturn(false);

        assertCode(
                ErrorCode.RECIPIENT_NOT_FOUND,
                () -> service.prepare(1L, ID.toString(), 2L)
        );

        verifyNoInteractions(tokens);
    }

    @Test
    @DisplayName("후속 요청은 로그인 사용자와 경로 UUID를 토큰 검증에 전달한다")
    void authorize_validatesTokenAndCurrentRecipient() {
        var identity = new ChatConversationIdentity(ID, 1L, 2L);

        when(users.isActive(1L)).thenReturn(true);
        when(users.isActive(2L)).thenReturn(true);

        when(tokens.verify("test-value", 1L, ID))
                .thenReturn(identity);

        assertEquals(
                identity,
                service.authorize(
                        1L,
                        ID.toString(),
                        "test-value"
                )
        );

        verify(tokens).verify("test-value", 1L, ID);
    }

    @Test
    @DisplayName("토큰 발급 후 수신자가 비활성화되면 후속 접근을 404로 거절한다")
    void authorize_rejectsRecipientThatBecameUnavailable() {
        when(users.isActive(1L)).thenReturn(true);

        when(tokens.verify("test-value", 1L, ID))
                .thenReturn(
                        new ChatConversationIdentity(ID, 1L, 2L)
                );

        assertCode(
                ErrorCode.NOT_FOUND,
                () -> service.authorize(
                        1L,
                        ID.toString(),
                        "test-value"
                )
        );
    }

    @Test
    @DisplayName("잘못된 경로 UUID와 수신자 범위는 400이다")
    void prepare_rejectsInvalidInput() {
        when(users.isActive(1L)).thenReturn(true);

        assertCode(
                ErrorCode.INVALID_REQUEST,
                () -> service.prepare(1L, "invalid", 2L)
        );

        assertCode(
                ErrorCode.INVALID_REQUEST,
                () -> service.prepare(1L, ID.toString(), 0L)
        );

        verifyNoInteractions(tokens);
    }

    private void assertCode(
            ErrorCode code,
            org.junit.jupiter.api.function.Executable executable
    ) {
        assertEquals(
                code,
                assertThrows(
                        BusinessException.class,
                        executable
                ).getErrorCode()
        );
    }
}
