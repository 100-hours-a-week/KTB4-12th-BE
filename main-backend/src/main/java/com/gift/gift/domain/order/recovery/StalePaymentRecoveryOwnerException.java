package com.gift.gift.domain.order.recovery;

public class StalePaymentRecoveryOwnerException extends RuntimeException {

    public StalePaymentRecoveryOwnerException() {
        super("결제 복구 작업의 소유권이 만료되었거나 변경되었습니다.");
    }
}
