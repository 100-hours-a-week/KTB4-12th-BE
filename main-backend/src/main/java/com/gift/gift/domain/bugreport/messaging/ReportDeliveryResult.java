package com.gift.gift.domain.bugreport.messaging;

public record ReportDeliveryResult(boolean success, boolean permanent, long retryAfterMs,
                                   boolean global, String errorCode) {
}
