package com.gift.gift.domain.notification.event.handler;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.repository.PaymentReceiptMailData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaymentReceiptEmailQueueSendTest {

    private static final String FROM_ADDRESS = "no-reply@example.com";
    private static final LocalDateTime PAYMENT_COMPLETED_AT = LocalDateTime.of(2026, 11, 7, 12, 30, 45);
    private static final LocalDateTime CANCELED_AT = LocalDateTime.of(2026, 11, 8, 10, 45, 0);

    private final JavaMailSender mailSender = mock(JavaMailSender.class);

    private PaymentReceiptEmailEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentReceiptEmailEventHandler(mailSender, FROM_ADDRESS);
    }

    @Test
    @DisplayName("결제 자료를 받으면 저장된 결제 시각을 포함한 메일을 보낸다")
    void sendFromQueue_paymentReceiptUsesStoredPaymentData() {
        PaymentReceiptMailData mailData = new PaymentReceiptMailData(
                "payer@example.com",
                "order-101",
                "테스트 선물",
                2,
                40_000,
                60_000,
                PAYMENT_COMPLETED_AT,
                null
        );

        handler.sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, mailData);

        SimpleMailMessage message = captureMessage();

        assertThat(message.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getTo()).containsExactly("payer@example.com");
        assertThat(message.getSubject()).isEqualTo("서비스 크레딧 사용 내역 영수증");
        assertThat(message.getText())
                .contains("사용 크레딧: 40,000 크레딧")
                .contains("사용 후 잔액: 60,000 크레딧")
                .contains("처리 시각: 2026-11-07 12:30:45");
    }

    @Test
    @DisplayName("취소 자료를 받으면 결제 완료 시각과 취소 처리 시각을 모두 넣는다")
    void sendFromQueue_cancellationReceiptIncludesBothTimes() {
        PaymentReceiptMailData mailData = new PaymentReceiptMailData(
                "payer@example.com",
                "order-101",
                "테스트 선물",
                2,
                40_000,
                100_000,
                PAYMENT_COMPLETED_AT,
                CANCELED_AT
        );

        handler.sendFromQueue(PaymentReceiptDeliveryType.CANCELLATION, mailData);

        SimpleMailMessage message = captureMessage();

        assertThat(message.getSubject()).isEqualTo("서비스 크레딧 복구 영수증");
        assertThat(message.getText())
                .contains("복구 크레딧: 40,000 크레딧")
                .contains("결제 완료 시각: 2026-11-07 12:30:45")
                .contains("취소 처리 시각: 2026-11-08 10:45:00");
    }

    @Test
    @DisplayName("큐 발송 경로는 SMTP 오류를 삼키지 않고 호출자에게 전달한다")
    void sendFromQueue_propagatesSmtpFailure() {
        PaymentReceiptMailData mailData = new PaymentReceiptMailData(
                "payer@example.com",
                "order-101",
                "테스트 선물",
                2,
                40_000,
                60_000,
                PAYMENT_COMPLETED_AT,
                null
        );

        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        assertThatThrownBy(() ->
                handler.sendFromQueue(PaymentReceiptDeliveryType.PAYMENT, mailData)
        ).isInstanceOf(MailSendException.class);
    }

    private SimpleMailMessage captureMessage() {
        var captor = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }
}
