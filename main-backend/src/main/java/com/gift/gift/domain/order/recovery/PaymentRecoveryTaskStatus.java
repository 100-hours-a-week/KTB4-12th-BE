package com.gift.gift.domain.order.recovery;

public enum PaymentRecoveryTaskStatus {
    WAITING_FOR_SYNC,
    READY,
    DISPATCHED,
    RUNNING,
    COMPLETED,
    REVIEW_REQUIRED
}
