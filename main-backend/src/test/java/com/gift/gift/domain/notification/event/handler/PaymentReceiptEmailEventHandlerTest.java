package com.gift.gift.domain.notification.event.handler;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.gift.gift.domain.order.event.PaymentReceiptEmailEvent;
import com.gift.gift.domain.order.event.PaymentRefundReceiptEmailEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptEmailEventHandlerTest {

    private static final String FROM_ADDRESS = "no-reply@example.com";
    private static final LocalDateTime RECEIPT_AT =
            LocalDateTime.of(2026, 10, 24, 12, 30, 45);

    @Mock
    private JavaMailSender mailSender;

    private PaymentReceiptEmailEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new PaymentReceiptEmailEventHandler(
                mailSender,
                FROM_ADDRESS
        );
    }

    @Test
    @DisplayName("크레딧 사용 이벤트를 받으면 사용 내역 영수증을 발송한다")
    void handle_sendsCreditUsageReceipt() {
        PaymentReceiptEmailEvent event = new PaymentReceiptEmailEvent(
                "payer@example.com",
                "order-123",
                "테스트 선물",
                2,
                40_000,
                60_000,
                RECEIPT_AT
        );

        handler.handle(event);

        SimpleMailMessage message = captureMessage();

        assertThat(message.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getTo()).containsExactly("payer@example.com");
        assertThat(message.getSubject())
                .isEqualTo("서비스 크레딧 사용 내역 영수증");
        assertThat(message.getText())
                .contains("실제 카드 결제 영수증이 아닙니다.")
                .contains("사용 크레딧: 40,000 크레딧")
                .contains("사용 후 잔액: 60,000 크레딧")
                .contains("처리 시각: 2026-10-24 12:30:45");
    }

    @Test
    @DisplayName("크레딧 복구 이벤트를 받으면 복구 내역 영수증을 발송한다")
    void handle_sendsCreditRefundReceipt() {
        PaymentRefundReceiptEmailEvent event =
                new PaymentRefundReceiptEmailEvent(
                        "payer@example.com",
                        "order-123",
                        "테스트 선물",
                        2,
                        40_000,
                        100_000,
                        RECEIPT_AT
                );

        handler.handle(event);

        SimpleMailMessage message = captureMessage();

        assertThat(message.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getTo()).containsExactly("payer@example.com");
        assertThat(message.getSubject())
                .isEqualTo("서비스 크레딧 복구 영수증");
        assertThat(message.getText())
                .contains("실제 카드 결제 취소 영수증이 아닙니다.")
                .contains("복구 크레딧: 40,000 크레딧")
                .contains("복구 후 잔액: 100,000 크레딧")
                .contains("취소 처리 시각: 2026-10-24 12:30:45");
    }

    @Test
    @DisplayName("SMTP 오류는 취소 완료 후처리로 전파하지 않는다")
    void handle_doesNotPropagateSmtpFailure() {
        PaymentRefundReceiptEmailEvent event =
                new PaymentRefundReceiptEmailEvent(
                        "payer@example.com",
                        "order-123",
                        "테스트 선물",
                        2,
                        40_000,
                        100_000,
                        RECEIPT_AT
                );

        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        assertThatCode(() -> handler.handle(event))
                .doesNotThrowAnyException();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    private SimpleMailMessage captureMessage() {
        ArgumentCaptor<SimpleMailMessage> messageCaptor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());
        return messageCaptor.getValue();
    }
}
