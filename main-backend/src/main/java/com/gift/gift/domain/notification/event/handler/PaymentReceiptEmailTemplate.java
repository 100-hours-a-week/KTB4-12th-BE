package com.gift.gift.domain.notification.event.handler;

final class PaymentReceiptEmailTemplate {

    static final String SUBJECT = "서비스 크레딧 사용 내역 영수증";

    static final String BODY_FORMAT = """
            안녕하세요.

            선잘알 서비스 내 크레딧 사용 내역입니다.
            본 메일은 실제 카드 결제 영수증이 아닙니다.

            주문번호: %s
            상품명: %s
            수량: %d개
            사용 크레딧: %s 크레딧
            사용 후 잔액: %s 크레딧
            처리 시각: %s

            이용해주셔서 감사합니다.
            """;

    static final String REFUND_SUBJECT = "서비스 크레딧 복구 영수증";

    static final String REFUND_BODY_FORMAT = """
            안녕하세요.

            선잘알 서비스 내 크레딧 복구 내역입니다.
            본 메일은 실제 카드 결제 취소 영수증이 아닙니다.

            주문번호: %s
            상품명: %s
            수량: %d개
            복구 크레딧: %s 크레딧
            복구 후 잔액: %s 크레딧
            취소 처리 시각: %s

            이용해주셔서 감사합니다.
            """;

    private PaymentReceiptEmailTemplate() {
    }
}
