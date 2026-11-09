package com.gift.gift.domain.order.recovery;

public record PaymentRecoveryTaskClaim(boolean managed, String token) {

    public static PaymentRecoveryTaskClaim unmanaged() {
        return new PaymentRecoveryTaskClaim(false, null);
    }

    public static PaymentRecoveryTaskClaim managed(String token) {
        return new PaymentRecoveryTaskClaim(true, token);
    }
}
