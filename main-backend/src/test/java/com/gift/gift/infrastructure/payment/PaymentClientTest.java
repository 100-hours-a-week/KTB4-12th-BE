package com.gift.gift.infrastructure.payment;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.client.PaymentCommandClient;
import com.gift.gift.infrastructure.payment.client.PaymentQueryClient;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentClientTest {

    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";

    private final PaymentCommandClient commandClient = mock(PaymentCommandClient.class);

    private final PaymentQueryClient queryClient = mock(PaymentQueryClient.class);

    private final PaymentClient client = new PaymentClient(commandClient, queryClient);

    @Test
    @DisplayName("성공 응답의 결제 데이터를 반환한다")
    void confirm_returnsPaymentData() {
        PaymentConfirmRequest request = new PaymentConfirmRequest(ORDER_ID, 64_000L);

        PaymentResponse payment = new PaymentResponse(
                ORDER_ID,
                "payment-key",
                "transaction-key",
                PaymentServerStatus.COMPLETED,
                64_000L,
                null
        );

        when(commandClient.confirm(request, null))
                .thenReturn(
                        new PaymentServerResponse<>("결제 요청을 처리했습니다.", payment)
                );

        assertThat(client.confirm(request, null)).isEqualTo(payment);
    }

    @Test
    @DisplayName("승인 응답 타임아웃은 결제 결과 미확정으로 처리한다")
    void confirm_mapsTimeoutToUnconfirmed() {
        PaymentConfirmRequest request = new PaymentConfirmRequest(ORDER_ID, 64_000L);

        when(commandClient.confirm(request, null))
                .thenThrow(
                        new ResourceAccessException("응답 타임아웃", new SocketTimeoutException())
                );

        assertThatThrownBy(() -> client.confirm(request, null))
                .isInstanceOfSatisfying(
                        PaymentClientException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED)
                );
    }

    @Test
    @DisplayName("결제 상태 조회 타임아웃은 서버 사용 불가로 처리한다")
    void getByOrderId_mapsTimeoutToUnavailable() {
        when(queryClient.getByOrderId(ORDER_ID))
                .thenThrow(
                        new ResourceAccessException("응답 타임아웃", new SocketTimeoutException())
                );

        assertThatThrownBy(
                () -> client.getByOrderId(ORDER_ID)
        ).isInstanceOfSatisfying(
                PaymentClientException.class,
                exception -> assertThat(exception.failure())
                        .isEqualTo(
                                PaymentClientFailure
                                        .PAYMENT_SERVER_UNAVAILABLE
                        )
        );
    }

    @Test
    @DisplayName("Payment Server 연결 실패는 서버 사용 불가로 처리한다")
    void confirm_mapsConnectionFailureToUnavailable() {
        PaymentConfirmRequest request = new PaymentConfirmRequest(ORDER_ID, 64_000L);

        when(commandClient.confirm(request, null))
                .thenThrow(
                        new ResourceAccessException("연결 실패", new ConnectException())
                );

        assertThatThrownBy(() -> client.confirm(request, null))
                .isInstanceOfSatisfying(
                        PaymentClientException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE)
                );
    }

    @Test
    @DisplayName("성공 응답에 data가 없으면 잘못된 응답으로 처리한다")
    void confirm_rejectsMissingData() {
        PaymentConfirmRequest request =
                new PaymentConfirmRequest(ORDER_ID, 64_000L);

        when(commandClient.confirm(request, null))
                .thenReturn(
                        new PaymentServerResponse<>(
                                "결제 요청을 처리했습니다.",
                                null
                        )
                );

        assertThatThrownBy(() -> client.confirm(request, null))
                .isInstanceOfSatisfying(
                        PaymentClientException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(PaymentClientFailure.INVALID_RESPONSE)
                );
    }
}
