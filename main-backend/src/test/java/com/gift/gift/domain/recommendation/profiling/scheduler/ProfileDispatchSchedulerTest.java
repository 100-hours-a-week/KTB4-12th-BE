package com.gift.gift.domain.recommendation.profiling.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.recommendation.profiling.service.ProfileDispatchService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProfileDispatchSchedulerTest {

    @Mock
    private ProfileDispatchService profileDispatchService;

    private ProfileDispatchScheduler scheduler;

    @Test
    @DisplayName("스케줄링·AMQP 활성화 네 조합 중 둘 다 켠 경우만 MQ 스케줄러를 등록한다")
    void registersOnlyWhenBothFlagsAreEnabled() {
        for (boolean scheduling : new boolean[]{false, true}) {
            for (boolean amqp : new boolean[]{false, true}) {
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withBean(ProfileDispatchService.class, () -> profileDispatchService)
                        .withUserConfiguration(ProfileDispatchScheduler.class)
                        .withPropertyValues("app.ai-profile.scheduling-enabled=" + scheduling,
                                "app.ai-profile.amqp.enabled=" + amqp)
                        .run(context -> {
                            org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                            org.assertj.core.api.Assertions.assertThat(
                                    context.getBeansOfType(ProfileDispatchScheduler.class))
                                    .hasSize(scheduling && amqp ? 1 : 0);
                        });
            }
        }
    }

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
