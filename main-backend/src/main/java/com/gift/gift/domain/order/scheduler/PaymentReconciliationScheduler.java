package com.gift.gift.domain.order.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.order.service.PaymentReconciliationService;

@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.interval:PT1M}")
    @SchedulerLock(
            name = "reconcilePendingPayments",
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT5S"
    )
    public void reconcilePendingPayments() {
        try {
            reconciliationService.reconcilePendingPayments();
        } catch (RuntimeException exception) {
            log.error("결제 재조정 Batch 실행에 실패했습니다.", exception);
        }
    }
}
