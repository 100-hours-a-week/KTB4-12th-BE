package com.gift.gift.domain.gift.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.gift.service.GiftExpirationService;

@Slf4j
@Component
@RequiredArgsConstructor
public class GiftExpirationScheduler {

    private final GiftExpirationService expirationService;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "expireUnacceptedGifts", lockAtMostFor = "PT1H")
    public void expireUnacceptedGifts() {
        try {
            expirationService.expireAll();
        } catch (RuntimeException exception) {
            log.error("미수락 선물 만료 Batch 실행에 실패했습니다.", exception);
        }
    }
}
