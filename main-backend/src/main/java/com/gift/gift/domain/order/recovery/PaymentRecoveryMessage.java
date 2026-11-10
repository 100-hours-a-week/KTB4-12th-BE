package com.gift.gift.domain.order.recovery;

public record PaymentRecoveryMessage(long taskId, long generation) {

    public PaymentRecoveryMessage {
        if (taskId <= 0 || generation <= 0) {
            throw new IllegalArgumentException(
                    "복구 메시지의 작업 ID와 generation은 양수여야 합니다."
            );
        }
    }
}
