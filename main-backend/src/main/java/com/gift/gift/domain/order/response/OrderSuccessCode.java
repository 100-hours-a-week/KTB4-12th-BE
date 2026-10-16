package com.gift.gift.domain.order.response;

import org.springframework.http.HttpStatus;

public enum OrderSuccessCode {

    PAYMENT_CREATED(
            HttpStatus.CREATED,
            "결제가 완료되었습니다."
    ),
    PAYMENT_REUSED(
            HttpStatus.OK,
            "결제가 완료되었습니다."
    ),
    PAYMENT_PROCESSING(
            HttpStatus.ACCEPTED,
            "결제를 처리하고 있습니다. 잠시 후 다시 확인해 주세요."
    ),
    PAYMENT_LOCAL_PROCESSING(
            HttpStatus.ACCEPTED,
            "결제는 승인되었으며 주문 처리를 완료하고 있습니다. 완료되면 알려드리겠습니다."
    ),
    PAYMENT_STATUS_RETRIEVED(
            HttpStatus.OK,
            "결제 상태를 조회했습니다."
    ),
    PAYMENT_CANCELLATION_CREATED(
            HttpStatus.CREATED,
            "결제와 선물을 취소했습니다."
    ),
    PAYMENT_CANCELLATION_REUSED(
            HttpStatus.OK,
            "결제와 선물을 취소했습니다."
    );

    private final HttpStatus status;
    private final String message;

    OrderSuccessCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    public String message() {
        return message;
    }
}
