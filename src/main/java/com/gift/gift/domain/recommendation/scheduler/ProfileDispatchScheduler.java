package com.gift.gift.domain.recommendation.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.recommendation.service.ProfileDispatchService;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileDispatchScheduler {

    private final ProfileDispatchService profileDispatchService;

    @Scheduled(
            fixedDelayString =
                    "${app.ai-profile.dispatch-interval:PT1M}"
    )
    public void dispatchProfiles() {
        try {
            profileDispatchService.dispatchDueProfiles();
        } catch (RuntimeException exception) {
            log.error(
                    "AI 프로파일링 Batch 실행에 실패했습니다.",
                    exception
            );
        }
    }
}
