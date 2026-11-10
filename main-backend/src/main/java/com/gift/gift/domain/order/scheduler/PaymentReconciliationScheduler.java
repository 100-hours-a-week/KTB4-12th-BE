package com.gift.gift.domain.order.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.gift.gift.domain.order.service.PaymentReconciliationService;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "!${app.payment.mq.topology.enabled:false} or "
                + "!${app.payment.recovery.dispatch.enabled:false}"
)
public class PaymentReconciliationScheduler {

    private final PaymentReconciliationService reconciliationService;

    @Scheduled(fixedDelayString = "${app.payment.reconciliation.interval:PT5S}")
    @SchedulerLock(
            name = "reconcilePendingPayments",
            lockAtMostFor = "PT1H",
            lockAtLeastFor = "${app.payment.reconciliation.interval:PT5S}"
    )
    public void reconcilePendingPayments() {
        try {
            reconciliationService.reconcilePendingPayments();
        } catch (RuntimeException exception) {
            log.error("결제 재조정 Batch 실행에 실패했습니다.", exception);
        }
    }
}
