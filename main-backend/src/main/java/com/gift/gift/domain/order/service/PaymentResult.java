package com.gift.gift.domain.order.service;

public record PaymentResult(
        PaymentCompletion completion,
        PaymentProcessingResult processing,
        boolean created
) {

    public PaymentResult {
        if ((completion == null) == (processing == null)) {
            throw new IllegalArgumentException(
                    "결제 완료 결과와 처리 중 결과 중 하나만 제공해야 합니다."
            );
        }
    }

    public static PaymentResult completed(
            PaymentCompletion completion,
            boolean created
    ) {
        return new PaymentResult(completion, null, created);
    }

    public static PaymentResult processing(
            PaymentProcessingResult processing,
            boolean created
    ) {
        return new PaymentResult(null, processing, created);
    }

    public boolean isProcessing() {
        return processing != null;
    }
}
