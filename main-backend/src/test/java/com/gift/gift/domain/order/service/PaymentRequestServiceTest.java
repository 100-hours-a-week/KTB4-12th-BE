package com.gift.gift.domain.order.service;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.support.PaymentRequestHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentRequestServiceTest {

    private static final Long PAYER_ID = 1L;
    private static final String IDEMPOTENCY_KEY = "payment-request-001";

    private OrderRepository orderRepository;
    private PaymentValidator paymentValidator;
    private OrderTransactionService transactionService;
    private PaymentRequestHasher requestHasher;
    private PaymentRequestService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentValidator = mock(PaymentValidator.class);
        transactionService = mock(OrderTransactionService.class);
        requestHasher = new PaymentRequestHasher();

        service = new PaymentRequestService(
                orderRepository,
                paymentValidator,
                transactionService,
                requestHasher
        );
    }

    @Test
    @DisplayName("기존 주문이 없으면 검증 후 PENDING 주문을 생성한다")
    void start_createsPendingOrder() {
        PaymentCreateRequest request = request(2);
        PaymentValidation validation = new PaymentValidation(32_000L, 64_000L);
        String requestHash = requestHasher.hash(request);
        Order order = order(request, requestHash);

        when(orderRepository.findByPayerIdAndIdempotencyKey(
                PAYER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(paymentValidator.validate(PAYER_ID, request))
                .thenReturn(validation);
        when(transactionService.create(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                requestHash,
                request,
                validation
        )).thenReturn(order);

        PaymentStartResult result = service.start(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                request
        );

        assertThat(result.order()).isSameAs(order);
        assertThat(result.created()).isTrue();
        assertThat(result.order().getStatus().name()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("같은 멱등 키와 요청이면 기존 주문을 반환한다")
    void start_returnsExistingOrder() {
        PaymentCreateRequest request = request(2);
        String requestHash = requestHasher.hash(request);
        Order order = order(request, requestHash);

        when(orderRepository.findByPayerIdAndIdempotencyKey(
                PAYER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(order));

        PaymentStartResult result = service.start(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                request
        );

        assertThat(result.order()).isSameAs(order);
        assertThat(result.created()).isFalse();

        verifyNoInteractions(paymentValidator, transactionService);
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 요청이면 충돌로 처리한다")
    void start_rejectsDifferentRequest() {
        PaymentCreateRequest existingRequest = request(1);
        PaymentCreateRequest newRequest = request(2);
        Order order = order(existingRequest, requestHasher.hash(existingRequest));

        when(orderRepository.findByPayerIdAndIdempotencyKey(
                PAYER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(order));

        assertThatThrownBy(
                () -> service.start(
                        PAYER_ID,
                        IDEMPOTENCY_KEY,
                        newRequest
                )
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT)
        );

        verifyNoInteractions(paymentValidator, transactionService);
    }

    @Test
    @DisplayName("멱등 키 UNIQUE 경합 시 먼저 생성된 주문을 재조회한다")
    void start_recoversIdempotencyRace() {
        PaymentCreateRequest request = request(2);
        PaymentValidation validation = new PaymentValidation(32_000L, 64_000L);
        String requestHash = requestHasher.hash(request);
        Order order = order(request, requestHash);

        when(orderRepository.findByPayerIdAndIdempotencyKey(
                PAYER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                Optional.empty(),
                Optional.of(order)
        );
        when(paymentValidator.validate(PAYER_ID, request))
                .thenReturn(validation);
        when(transactionService.create(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                requestHash,
                request,
                validation
        )).thenThrow(
                duplicate("uk_orders_payer_idempotency")
        );

        PaymentStartResult result = service.start(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                request
        );

        assertThat(result.order()).isSameAs(order);
        assertThat(result.created()).isFalse();
    }

    @Test
    @DisplayName("다른 DB 무결성 오류는 그대로 전파한다")
    void start_propagatesOtherIntegrityViolation() {
        PaymentCreateRequest request = request(2);
        PaymentValidation validation = new PaymentValidation(32_000L, 64_000L);
        String requestHash = requestHasher.hash(request);
        DataIntegrityViolationException failure = duplicate("uk_orders_order_id");

        when(orderRepository.findByPayerIdAndIdempotencyKey(
                PAYER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(paymentValidator.validate(PAYER_ID, request)).thenReturn(validation);
        when(transactionService.create(
                PAYER_ID,
                IDEMPOTENCY_KEY,
                requestHash,
                request,
                validation
        )).thenThrow(failure);

        assertThatThrownBy(
                () -> service.start(
                        PAYER_ID,
                        IDEMPOTENCY_KEY,
                        request
                )
        ).isSameAs(failure);
    }

    private PaymentCreateRequest request(int quantity) {
        return new PaymentCreateRequest(
                101L,
                27L,
                quantity,
                32_000L
        );
    }

    private Order order(PaymentCreateRequest request, String requestHash) {

        long amount = request.expectedUnitPrice() * request.quantity();

        return new Order(
                "550e8400-e29b-41d4-a716-446655440000",
                PAYER_ID,
                request.recipientUserId(),
                request.productId(),
                request.quantity(),
                request.expectedUnitPrice(),
                amount,
                IDEMPOTENCY_KEY,
                requestHash
        );
    }

    private DataIntegrityViolationException duplicate(String constraint) {

        SQLIntegrityConstraintViolationException cause =
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'orders."
                                + constraint
                                + "'",
                        "23000",
                        1062
                );

        return new DataIntegrityViolationException("주문 UNIQUE 제약 위반", cause);
    }
}
