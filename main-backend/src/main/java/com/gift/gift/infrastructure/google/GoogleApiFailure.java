package com.gift.gift.infrastructure.google;

import com.gift.gift.domain.bugreport.messaging.ReportDeliveryResult;

final class GoogleApiFailure extends RuntimeException {
    private final ReportDeliveryResult result;

    GoogleApiFailure(boolean permanent, long delayMs, boolean projectQuota, String code) {
        super(code);
        this.result = new ReportDeliveryResult(false, permanent, delayMs, projectQuota, code);
    }

    ReportDeliveryResult result() {
        return result;
    }
}
