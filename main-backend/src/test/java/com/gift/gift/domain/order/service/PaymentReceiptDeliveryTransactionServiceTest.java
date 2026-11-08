package com.gift.gift.domain.order.service;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.order.repository.PaymentReceiptDelivery;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryRepository;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryStatus;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.repository.PaymentReceiptMailData;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Action;
import com.gift.gift.domain.order.service.PaymentReceiptDeliveryTransactionService.Claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentReceiptDeliveryTransactionServiceTest {

    private static final PaymentReceiptDeliveryType RECEIPT_TYPE = PaymentReceiptDeliveryType.PAYMENT;
    private static final long SOURCE_ID = 101L;
    private static final LocalDateTime PAYMENT_COMPLETED_AT = LocalDateTime.of(2026, 11, 7, 12, 30);

    private static final PaymentReceiptMailData MAIL_DATA = new PaymentReceiptMailData(
            "payer@example.com",
            "order-101",
            "테스트 선물",
            2,
            20_000L,
            80_000L,
            PAYMENT_COMPLETED_AT,
            null
    );

    @Mock
    private PaymentReceiptDeliveryRepository repository;

    private PaymentReceiptDeliveryTransactionService service;

    @BeforeEach
    void setUp() {
        service = new PaymentReceiptDeliveryTransactionService(repository);
    }

    @Test
    @DisplayName("발송 준비된 결제 영수증을 선점하면 발송 자료와 증가된 시도 횟수를 반환한다")
    void claim_readyDelivery_returnsSendClaim() {
        when(repository.findForUpdate(RECEIPT_TYPE, SOURCE_ID))
                .thenReturn(Optional.of(new PaymentReceiptDelivery(
                        PaymentReceiptDeliveryStatus.READY,
                        0,
                        null,
                        null,
                        false,
                        null,
                        null
                )));
        when(repository.findMailData(RECEIPT_TYPE, SOURCE_ID))
                .thenReturn(Optional.of(MAIL_DATA));
        when(repository.claimAutomatic(eq(RECEIPT_TYPE), eq(SOURCE_ID), anyString()))
                .thenReturn(1);

        Claim claim = service.claim(RECEIPT_TYPE, SOURCE_ID, null);

        assertThat(claim.action()).isEqualTo(Action.SEND);
        assertThat(claim.claimToken()).isNotBlank();
        assertThat(claim.mailData()).isEqualTo(MAIL_DATA);
        assertThat(claim.autoAttempts()).isEqualTo(1);
        verify(repository).claimAutomatic(eq(RECEIPT_TYPE), eq(SOURCE_ID), anyString());
    }

    @Test
    @DisplayName("이미 발송된 영수증 메시지는 다시 발송하지 않고 ACK 대상으로 반환한다")
    void claim_sentDeliveryReturnsAckWithoutLoadingMailData() {
        when(repository.findForUpdate(RECEIPT_TYPE, SOURCE_ID))
                .thenReturn(Optional.of(new PaymentReceiptDelivery(
                        PaymentReceiptDeliveryStatus.SENT,
                        1,
                        null,
                        null,
                        false,
                        null,
                        null
                )));

        Claim claim = service.claim(RECEIPT_TYPE, SOURCE_ID, null);

        assertThat(claim.action()).isEqualTo(Action.ACK);
        verify(repository, never()).findMailData(RECEIPT_TYPE, SOURCE_ID);
    }

    @Test
    @DisplayName("자동 발송 중 일시 오류는 재시도 대상으로 기록한다")
    void recordFailure_retryableErrorReturnsRetry() {
        Claim claim = new Claim(
                Action.SEND,
                RECEIPT_TYPE,
                SOURCE_ID,
                "claim-token",
                MAIL_DATA,
                false,
                1
        );
        when(repository.recordFailure(
                RECEIPT_TYPE,
                SOURCE_ID,
                "claim-token",
                "SMTP_TEMPORARY",
                true
        )).thenReturn(1);

        Action result = service.recordFailure(claim, "SMTP_TEMPORARY", false);

        assertThat(result).isEqualTo(Action.RETRY);
        verify(repository).recordFailure(
                RECEIPT_TYPE,
                SOURCE_ID,
                "claim-token",
                "SMTP_TEMPORARY",
                true
        );
    }
}
