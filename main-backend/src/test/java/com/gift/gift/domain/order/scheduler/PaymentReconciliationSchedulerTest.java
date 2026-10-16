package com.gift.gift.domain.order.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.order.service.PaymentReconciliationService;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentReconciliationSchedulerTest {

    @Test
    @DisplayName("스케줄 실행 시 결제 재조정 서비스를 호출한다")
    void reconcilePendingPayments_delegatesToService() {
        PaymentReconciliationService service = mock(PaymentReconciliationService.class);
        PaymentReconciliationScheduler scheduler = new PaymentReconciliationScheduler(service);

        scheduler.reconcilePendingPayments();

        verify(service).reconcilePendingPayments();
    }
}
