package com.gift.gift.domain.recommendation.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.recommendation.service.ProfileDispatchService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileDispatchSchedulerTest {

    @Mock
    private ProfileDispatchService profileDispatchService;

    private ProfileDispatchScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new ProfileDispatchScheduler(profileDispatchService);
    }

    @Test
    @DisplayName("스케줄러 실행을 프로파일 전송 서비스에 위임한다")
    void dispatchProfiles_delegatesToService() {
        scheduler.dispatchProfiles();

        verify(profileDispatchService).dispatchDueProfiles();
    }

    @Test
    @DisplayName("서비스의 예상하지 않은 예외를 스케줄러 경계에서 전파하지 않는다")
    void dispatchProfiles_isolatesUnexpectedBatchFailure() {
        doThrow(new IllegalStateException("batch failed"))
                .when(profileDispatchService)
                .dispatchDueProfiles();

        assertDoesNotThrow(scheduler::dispatchProfiles);
        verify(profileDispatchService).dispatchDueProfiles();
    }
}
