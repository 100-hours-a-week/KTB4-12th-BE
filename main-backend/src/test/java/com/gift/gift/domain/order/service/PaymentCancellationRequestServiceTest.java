package com.gift.gift.domain.order.service;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.order.support.PaymentCancellationRequestHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class PaymentCancellationRequestServiceTest {

    private static final Long REQUESTER_ID = 1L;
    private static final Long ORDER_RECORD_ID = 10L;
    private static final String ORDER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String IDEMPOTENCY_KEY = "cancel-request-001";

    private OrderRepository orderRepository;
    private GiftHistoryRepository giftHistoryRepository;
    private PaymentCancellationRepository cancellationRepository;
    private PaymentCancellationTransactionService transactionService;
    private PaymentCancellationRequestHasher requestHasher;
    private PaymentCancellationRequestService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        cancellationRepository = mock(PaymentCancellationRepository.class);
        transactionService = mock(PaymentCancellationTransactionService.class);
        requestHasher = new PaymentCancellationRequestHasher();

        service = new PaymentCancellationRequestService(
                orderRepository,
                giftHistoryRepository,
                cancellationRepository,
                transactionService,
                requestHasher
        );
    }

    @Test
    @DisplayName("최초 취소 요청은 검증 후 PENDING 취소를 생성한다")
    void start_createsPendingCancellation() {
        Order order = completedOrder();
        PaymentCancellation cancellation = cancellation(order);
        GiftHistory gift = waitingGift();

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, REQUESTER_ID))
                .thenReturn(Optional.of(order));
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(transactionService.create(
                order,
                REQUESTER_ID,
                CancellationSource.USER,
                IDEMPOTENCY_KEY,
                requestHasher.hash(ORDER_ID)
        )).thenReturn(cancellation);

        PaymentCancellationStartResult result = service.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.cancellation()).isSameAs(cancellation);
        assertThat(result.order()).isSameAs(order);
        assertThat(result.created()).isTrue();
    }

    @Test
    @DisplayName("승인된 PENDING 주문은 선물 이력 없이 자동 보상 취소를 생성한다")
    void start_createsAutomaticCancellationForApprovedOrder() {
        Order order = approvedPendingOrder();
        String idempotencyKey = "LOCAL_PROCESSING_FAILURE:" + ORDER_ID;
        PaymentCancellation cancellation = new PaymentCancellation(
                ORDER_RECORD_ID,
                REQUESTER_ID,
                CancellationSource.LOCAL_PROCESSING_FAILURE,
                idempotencyKey,
                requestHasher.hash(ORDER_ID)
        );

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                idempotencyKey
        )).thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, REQUESTER_ID))
                .thenReturn(Optional.of(order));
        when(transactionService.create(
                order,
                REQUESTER_ID,
                CancellationSource.LOCAL_PROCESSING_FAILURE,
                idempotencyKey,
                requestHasher.hash(ORDER_ID)
        )).thenReturn(cancellation);

        PaymentCancellationStartResult result = service.start(
                REQUESTER_ID,
                ORDER_ID,
                idempotencyKey,
                CancellationSource.LOCAL_PROCESSING_FAILURE
        );

        assertThat(result.cancellation()).isSameAs(cancellation);
        verifyNoInteractions(giftHistoryRepository);
    }

    @Test
    @DisplayName("같은 멱등 키와 주문번호이면 기존 취소 요청을 반환한다")
    void start_returnsExistingCancellation() {
        Order order = completedOrder();
        PaymentCancellation cancellation = cancellation(order);

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(cancellation));
        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(order));

        PaymentCancellationStartResult result = service.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.cancellation()).isSameAs(cancellation);
        assertThat(result.created()).isFalse();
        verifyNoInteractions(giftHistoryRepository, transactionService);
    }

    @Test
    @DisplayName("같은 멱등 키에 다른 주문번호를 사용하면 충돌로 처리한다")
    void start_rejectsDifferentOrder() {
        Order order = completedOrder();
        PaymentCancellation cancellation = cancellation(order);
        String otherOrderId = "550e8400-e29b-41d4-a716-446655440001";

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.of(cancellation));

        assertThatThrownBy(() -> service.start(
                REQUESTER_ID,
                otherOrderId,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT)
        );

        verifyNoInteractions(orderRepository, giftHistoryRepository, transactionService);
    }

    @Test
    @DisplayName("본인 소유 주문을 찾을 수 없으면 결제 없음으로 처리한다")
    void start_rejectsUnknownOwnedOrder() {
        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, REQUESTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_FOUND)
        );
    }

    @Test
    @DisplayName("수락 대기 중인 선물이 아니면 취소할 수 없다")
    void start_rejectsNonWaitingGift() {
        Order order = completedOrder();
        GiftHistory gift = mock(GiftHistory.class);

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(Optional.empty());
        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, REQUESTER_ID))
                .thenReturn(Optional.of(order));
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(gift.getStatus()).thenReturn(GiftStatus.COMPLETED);

        assertThatThrownBy(() -> service.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_NOT_CANCELABLE)
        );

        verifyNoInteractions(transactionService);
    }

    @Test
    @DisplayName("멱등 UNIQUE 경합은 다른 요청이 생성한 취소를 재조회한다")
    void start_recoversIdempotencyRace() {
        Order order = completedOrder();
        PaymentCancellation cancellation = cancellation(order);
        GiftHistory gift = waitingGift();

        when(cancellationRepository.findByRequesterIdAndIdempotencyKey(
                REQUESTER_ID,
                IDEMPOTENCY_KEY
        )).thenReturn(
                Optional.empty(),
                Optional.of(cancellation)
        );
        when(orderRepository.findByOrderIdAndPayerId(ORDER_ID, REQUESTER_ID))
                .thenReturn(Optional.of(order));
        when(orderRepository.findById(ORDER_RECORD_ID))
                .thenReturn(Optional.of(order));
        when(giftHistoryRepository.findByOrderRecordId(ORDER_RECORD_ID))
                .thenReturn(Optional.of(gift));
        when(transactionService.create(
                order,
                REQUESTER_ID,
                CancellationSource.USER,
                IDEMPOTENCY_KEY,
                requestHasher.hash(ORDER_ID)
        )).thenThrow(duplicate());

        PaymentCancellationStartResult result = service.start(
                REQUESTER_ID,
                ORDER_ID,
                IDEMPOTENCY_KEY,
                CancellationSource.USER
        );

        assertThat(result.cancellation()).isSameAs(cancellation);
        assertThat(result.created()).isFalse();
    }

    private Order completedOrder() {
        Order order = new Order(
                ORDER_ID,
                REQUESTER_ID,
                2L,
                3L,
                2,
                32_000L,
                64_000L,
                "payment-request-001",
                "a".repeat(64)
        );

        ReflectionTestUtils.setField(order, "id", ORDER_RECORD_ID);
        ReflectionTestUtils.setField(order, "status", OrderStatus.COMPLETED);
        ReflectionTestUtils.setField(
                order,
                "paymentKey",
                "7d9f6d7e-a34c-4bbc-97d6-33a2b43c183a"
        );

        return order;
    }

    private Order approvedPendingOrder() {
        Order order = completedOrder();
        ReflectionTestUtils.setField(order, "status", OrderStatus.PENDING);
        ReflectionTestUtils.setField(
                order,
                "approvalResult",
                PaymentApprovalResult.APPROVED
        );
        return order;
    }

    private PaymentCancellation cancellation(Order order) {
        return new PaymentCancellation(
                order.getId(),
                REQUESTER_ID,
                CancellationSource.USER,
                IDEMPOTENCY_KEY,
                requestHasher.hash(ORDER_ID)
        );
    }

    private GiftHistory waitingGift() {
        GiftHistory gift = mock(GiftHistory.class);
        when(gift.getStatus()).thenReturn(GiftStatus.WAITING_FOR_ACCEPTANCE);
        when(gift.getDeletedAt()).thenReturn(null);
        return gift;
    }

    private DataIntegrityViolationException duplicate() {
        SQLIntegrityConstraintViolationException cause =
                new SQLIntegrityConstraintViolationException(
                        "Duplicate entry for key 'payment_cancellations."
                                + "uk_payment_cancellations_requester_idempotency'",
                        "23000",
                        1062
                );

        return new DataIntegrityViolationException(
                "취소 요청 UNIQUE 제약 위반",
                cause
        );
    }
}
