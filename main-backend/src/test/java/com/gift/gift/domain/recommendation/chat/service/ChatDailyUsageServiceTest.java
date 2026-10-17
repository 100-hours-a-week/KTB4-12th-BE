package com.gift.gift.domain.recommendation.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.recommendation.chat.entity.AiChatTurnUsage;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.repository.AiChatTurnUsageRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ChatDailyUsageServiceTest {

    private static final Long USER_ID = 1L;
    private static final UUID CONVERSATION_ID = UUID.fromString(
            "22222222-2222-4222-8222-222222222222"
    );
    private static final UUID TURN_ID = UUID.fromString(
            "11111111-1111-4111-8111-111111111111"
    );
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);

    private AiChatTurnUsageRepository usageRepository;
    private UserQueryService userQueryService;
    private ChatDailyUsageService service;

    @BeforeEach
    void setUp() {
        usageRepository = mock(AiChatTurnUsageRepository.class);
        userQueryService = mock(UserQueryService.class);
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-16T15:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        when(userQueryService.findActiveUserForUpdate(USER_ID))
                .thenReturn(Optional.of(mock(User.class)));

        service = new ChatDailyUsageService(
                usageRepository,
                userQueryService,
                clock
        );
    }

    @Test
    @DisplayName("신규 질문은 오늘 사용량이 9회이면 10번째 기록으로 저장한다")
    void consume_savesTenthUsage() {
        when(usageRepository.countByUser_IdAndUsageDate(USER_ID, TODAY))
                .thenReturn(9L);

        service.consume(USER_ID, CONVERSATION_ID, TURN_ID);

        ArgumentCaptor<AiChatTurnUsage> captor =
                ArgumentCaptor.forClass(AiChatTurnUsage.class);
        verify(usageRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getConversationId())
                .isEqualTo(CONVERSATION_ID);
        assertThat(captor.getValue().getTurnId()).isEqualTo(TURN_ID);
        assertThat(captor.getValue().getUsageDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("동일 질문 재시도는 사용량을 다시 확인하거나 저장하지 않는다")
    void consume_ignoresExistingTurn() {
        when(usageRepository.existsByUser_IdAndConversationIdAndTurnId(
                USER_ID,
                CONVERSATION_ID,
                TURN_ID
        ))
                .thenReturn(true);

        service.consume(USER_ID, CONVERSATION_ID, TURN_ID);

        verify(usageRepository, never())
                .countByUser_IdAndUsageDate(anyLong(), any());
        verify(usageRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("오늘 사용량이 10회이면 신규 질문을 거부한다")
    void consume_rejectsEleventhUsage() {
        when(usageRepository.countByUser_IdAndUsageDate(USER_ID, TODAY))
                .thenReturn(10L);

        assertThatThrownBy(() -> service.consume(
                USER_ID,
                CONVERSATION_ID,
                TURN_ID
        )).isInstanceOfSatisfying(
                ChatException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                ChatErrorCode.DAILY_LIMIT_EXCEEDED.errorCode()
                        )
        );

        verify(usageRepository, never()).saveAndFlush(any());
    }
}
