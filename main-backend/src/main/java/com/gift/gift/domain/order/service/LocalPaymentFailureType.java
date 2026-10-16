package com.gift.gift.domain.order.service;

public enum LocalPaymentFailureType {
    DEADLOCK,
    TRANSIENT_INFRASTRUCTURE,
    PERMANENT_BUSINESS,
    DATA_INTEGRITY_OR_PROGRAMMING,
    UNKNOWN
}
