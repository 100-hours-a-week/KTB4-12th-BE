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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptEmailEventHandlerTest {

    private static final String FROM_ADDRESS = "no-reply@example.com";
    private static final LocalDateTime COMPLETED_AT =
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
    @DisplayName("결제 완료 이벤트를 받으면 모의 크레딧 영수증 메일을 보낸다")
    void handle_sendsCreditUsageReceipt() {
        PaymentReceiptEmailEvent event = new PaymentReceiptEmailEvent(
                "payer@example.com",
                "order-123",
                "테스트 선물",
                2,
                40_000,
                60_000,
                COMPLETED_AT
        );

        handler.handle(event);

        ArgumentCaptor<SimpleMailMessage> messageCaptor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        assertThat(message.getFrom()).isEqualTo(FROM_ADDRESS);
        assertThat(message.getTo()).containsExactly("payer@example.com");
        assertThat(message.getSubject()).isEqualTo("서비스 크레딧 사용 내역 영수증");
        assertThat(message.getText())
                .contains("실제 카드 결제 영수증이 아닙니다.")
                .contains("주문번호: order-123")
                .contains("상품명: 테스트 선물")
                .contains("수량: 2개")
                .contains("사용 크레딧: 40,000 크레딧")
                .contains("사용 후 잔액: 60,000 크레딧")
                .contains("처리 시각: 2026-10-24 12:30:45")
                .contains("이용해주셔서 감사합니다.");
    }

    @Test
    @DisplayName("SMTP 오류가 발생해도 결제 완료 후처리에서 예외를 전파하지 않는다")
    void handle_doesNotPropagateSmtpFailure() {
        PaymentReceiptEmailEvent event = new PaymentReceiptEmailEvent(
                "payer@example.com",
                "order-123",
                "테스트 선물",
                2,
                40_000,
                60_000,
                COMPLETED_AT
        );

        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        assertThatCode(() -> handler.handle(event))
                .doesNotThrowAnyException();

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
