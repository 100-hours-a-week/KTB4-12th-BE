package com.gift.gift.domain.order.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
class PaymentFlowIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final long INITIAL_BALANCE = 500_000L;
    private static final long UNIT_PRICE = 32_000L;
    private static final int QUANTITY = 2;
    private static final long AMOUNT = UNIT_PRICE * QUANTITY;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRequestService paymentRequestService;

    @Autowired
    private PaymentCancellationService paymentCancellationService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private PaymentCancellationRepository paymentCancellationRepository;

    @Autowired
    private GiftHistoryRepository giftHistoryRepository;

    @Autowired
    private CreditTransactionRepository
            creditTransactionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private PaymentClient paymentClient;

    private TransactionTemplate transaction;
    private Long payerId;
    private Long recipientId;
    private Long productId;
    private Long rootCategoryId;
    private Long leafCategoryId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(
                transactionManager
        );

        String suffix = UUID.randomUUID().toString();

        transaction.executeWithoutResult(status -> {
            User payer = new User(
                    "payer-" + suffix + "@example.com",
                    PASSWORD_HASH,
                    "결제자",
                    LocalDate.of(1990, 1, 1)
            );
            User recipient = new User(
                    "recipient-" + suffix + "@example.com",
                    PASSWORD_HASH,
                    "수신자",
                    LocalDate.of(1990, 1, 1)
            );

            Category root = new Category(
                    "결제 대분류-" + suffix,
                    null
            );
            Category leaf = new Category(
                    "결제 소분류-" + suffix,
                    root
            );
            Product product = new Product(
                    leaf,
                    "결제 테스트 상품",
                    "테스트 브랜드",
                    null,
                    BigDecimal.valueOf(UNIT_PRICE),
                    10
            );

            entityManager.persist(payer);
            entityManager.persist(recipient);
            entityManager.persist(root);
            entityManager.persist(leaf);
            entityManager.persist(product);
            entityManager.persist(
                    new Friendship(payer, recipient)
            );
            entityManager.persist(
                    new CreditAccount(
                            payer.getId(),
                            INITIAL_BALANCE
                    )
            );
            entityManager.flush();

            payerId = payer.getId();
            recipientId = recipient.getId();
            productId = product.getId();
            rootCategoryId = root.getId();
            leafCategoryId = leaf.getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                """
                delete from credit_transactions
                where user_id = ?
                """,
                payerId
        );
        jdbcTemplate.update(
                """
                delete from gift_histories
                where sender_id = ?
                   or recipient_id = ?
                """,
                payerId,
                recipientId
        );
        jdbcTemplate.update(
                "delete from payment_cancellations where requester_id = ?",
                payerId
        );
        jdbcTemplate.update(
                "delete from orders where payer_id = ?",
                payerId
        );
        jdbcTemplate.update(
                "delete from credit_accounts where user_id = ?",
                payerId
        );
        jdbcTemplate.update(
                """
                delete from friendships
                where user_id_1 in (?, ?)
                   or user_id_2 in (?, ?)
                """,
                payerId,
                recipientId,
                payerId,
                recipientId
        );
        jdbcTemplate.update(
                "delete from products where id = ?",
                productId
        );
        jdbcTemplate.update(
                "delete from categories where id = ?",
                leafCategoryId
        );
        jdbcTemplate.update(
                "delete from categories where id = ?",
                rootCategoryId
        );
        jdbcTemplate.update(
                "delete from users where id in (?, ?)",
                payerId,
                recipientId
        );
    }

    @Test
    @DisplayName("승인 결제는 주문·재고·크레딧·선물·원장을 한 번만 반영한다")
    void pay_completesLocalTransactionOnlyOnce() {
        String idempotencyKey =
                "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        stubApproval();

        PaymentResult first = paymentService.pay(
                payerId,
                idempotencyKey,
                "APPROVE",
                request
        );
        PaymentResult second = paymentService.pay(
                payerId,
                idempotencyKey,
                "APPROVE",
                request
        );

        Order order = orderRepository
                .findByOrderId(
                        first.completion().orderId()
                )
                .orElseThrow();

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(order.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getApprovalResult())
                .isEqualTo(PaymentApprovalResult.APPROVED);

        assertThat(readProductQuantity()).isEqualTo(8);
        assertThat(readProductSales()).isEqualTo(2);
        assertThat(readBalance())
                .isEqualTo(INITIAL_BALANCE - AMOUNT);

        assertThat(
                giftHistoryRepository
                        .findByOrderRecordId(order.getId())
        ).get()
                .extracting(gift -> gift.getStatus())
                .isEqualTo(
                        GiftStatus.WAITING_FOR_ACCEPTANCE
                );

        assertThat(
                creditTransactionRepository
                        .findByDeduplicationKey(
                                "PAYMENT_USE:" + order.getId()
                        )
        ).get()
                .satisfies(ledger -> {
                    assertThat(ledger.getType())
                            .isEqualTo(
                                    CreditTransactionType.PAYMENT_USE
                            );
                    assertThat(ledger.getAmount())
                            .isEqualTo(AMOUNT);
                    assertThat(ledger.getBalanceAfter())
                            .isEqualTo(
                                    INITIAL_BALANCE - AMOUNT
                            );
                });

        verify(paymentClient, times(1))
                .confirm(any(PaymentConfirmRequest.class), eq("APPROVE"));
    }

    @Test
    @DisplayName("크레딧 원장 저장 실패 시 주문·재고·크레딧·선물을 모두 롤백한다")
    void pay_rollsBackLocalChanges_whenLedgerSaveFails() {

        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        PaymentStartResult start =
                paymentRequestService.start(
                        payerId,
                        idempotencyKey,
                        request
                );

        Order pendingOrder = start.order();

        transaction.executeWithoutResult(status ->
                creditTransactionRepository.saveAndFlush(
                        new CreditTransaction(
                                payerId,
                                CreditTransactionType.PAYMENT_USE,
                                AMOUNT,
                                INITIAL_BALANCE,
                                pendingOrder.getId(),
                                null,
                                null,
                                "PAYMENT_USE:"
                                        + pendingOrder.getId()
                        )
                )
        );

        stubApproval();

        assertThatThrownBy(() -> paymentService.pay(
                payerId,
                idempotencyKey,
                "APPROVE",
                request
        )).isInstanceOf(
                DataIntegrityViolationException.class
        );

        Order found = orderRepository.findById(pendingOrder.getId()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(found.getApprovalResult()).isEqualTo(PaymentApprovalResult.APPROVED);
        assertThat(found.getFailureCode()).isEqualTo(OrderFailureCode.LOCAL_PROCESSING_FAILED);

        assertThat(readProductQuantity()).isEqualTo(10);
        assertThat(readProductSales()).isZero();
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE);
        assertThat(
                giftHistoryRepository.findByOrderRecordId(
                        pendingOrder.getId()
                )
        ).isEmpty();
    }

    @Test
    @DisplayName("승인 응답 유실 후 상태 조회 결과가 완료이면 로컬 결제를 완료한다")
    void pay_recoversCompletedPayment_whenApprovalResponseIsLost() {
        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        when(paymentClient.confirm(
                any(PaymentConfirmRequest.class),
                eq("TIMEOUT_AFTER_APPROVAL")
        )).thenThrow(clientFailure(
                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
        ));

        when(paymentClient.getByOrderId(anyString()))
                .thenAnswer(invocation -> completedResponse(
                        invocation.getArgument(0),
                        AMOUNT
                ));

        PaymentResult result = paymentService.pay(
                payerId,
                idempotencyKey,
                "TIMEOUT_AFTER_APPROVAL",
                request
        );

        Order order = orderRepository
                .findByOrderId(result.completion().orderId())
                .orElseThrow();

        assertThat(result.created()).isTrue();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(order.getApprovalResult()).isEqualTo(PaymentApprovalResult.APPROVED);
        assertThat(readProductQuantity()).isEqualTo(8);
        assertThat(readProductSales()).isEqualTo(2);
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

        assertThat(giftHistoryRepository.findByOrderRecordId(order.getId())).isPresent();

        assertThat(
                creditTransactionRepository.findByDeduplicationKey("PAYMENT_USE:" + order.getId())).
                isPresent();

        verify(paymentClient, times(1))
                .confirm(any(PaymentConfirmRequest.class), eq("TIMEOUT_AFTER_APPROVAL"));
        verify(paymentClient, times(1)).getByOrderId(order.getOrderId());
    }

    @Test
    @DisplayName("미확정 주문 재요청은 승인 요청 없이 기존 상태 조회로 복구한다")
    void pay_queriesFirst_whenUnknownOrderIsRetried() {
        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        when(paymentClient.confirm(
                any(PaymentConfirmRequest.class),
                eq("TIMEOUT_AFTER_APPROVAL")
        )).thenThrow(clientFailure(
                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
        ));

        when(paymentClient.getByOrderId(anyString()))
                .thenThrow(clientFailure(
                        PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                ))
                .thenAnswer(invocation -> completedResponse(
                        invocation.getArgument(0),
                        AMOUNT
                ));

        assertThatThrownBy(() -> paymentService.pay(
                payerId,
                idempotencyKey,
                "TIMEOUT_AFTER_APPROVAL",
                request
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        ErrorCode.PAYMENT_RESULT_UNCONFIRMED
                )
        );

        Order unknownOrder = orderRepository
                .findByPayerIdAndIdempotencyKey(
                        payerId,
                        idempotencyKey
                )
                .orElseThrow();

        assertThat(unknownOrder.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(unknownOrder.getApprovalResult()).isEqualTo(PaymentApprovalResult.UNKNOWN);
        assertThat(unknownOrder.getFailureCode()).isEqualTo(OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED);

        PaymentResult recovered = paymentService.pay(
                payerId,
                idempotencyKey,
                "TIMEOUT_AFTER_APPROVAL",
                request
        );

        Order completedOrder = orderRepository.findById(unknownOrder.getId()).orElseThrow();

        assertThat(recovered.created()).isFalse();
        assertThat(completedOrder.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(completedOrder.getApprovalResult()).isEqualTo(PaymentApprovalResult.APPROVED);
        assertThat(readProductQuantity()).isEqualTo(8);
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

        verify(paymentClient, times(1))
                .confirm(
                        any(PaymentConfirmRequest.class),
                        eq("TIMEOUT_AFTER_APPROVAL")
                );
        verify(paymentClient, times(2))
                .getByOrderId(unknownOrder.getOrderId());
    }

    @Test
    @DisplayName("상태 조회에 주문이 없으면 같은 주문번호로 승인을 한 번 재요청한다")
    void pay_retriesApprovalOnce_whenPaymentIsNotFound() {
        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        when(paymentClient.confirm(
                any(PaymentConfirmRequest.class),
                eq("TIMEOUT_BEFORE_APPROVAL")
        )).thenThrow(clientFailure(
                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
        )).thenAnswer(invocation -> {
            PaymentConfirmRequest confirmation =
                    invocation.getArgument(0);

            return completedResponse(
                    confirmation.orderId(),
                    confirmation.amount()
            );
        });

        when(paymentClient.getByOrderId(anyString()))
                .thenThrow(clientFailure(
                        PaymentClientFailure.PAYMENT_NOT_FOUND
                ));

        PaymentResult result = paymentService.pay(
                payerId,
                idempotencyKey,
                "TIMEOUT_BEFORE_APPROVAL",
                request
        );

        Order order = orderRepository
                .findByOrderId(result.completion().orderId())
                .orElseThrow();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(readProductQuantity()).isEqualTo(8);
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE - AMOUNT);

        verify(paymentClient, times(2))
                .confirm(
                        any(PaymentConfirmRequest.class),
                        eq("TIMEOUT_BEFORE_APPROVAL")
                );
        verify(paymentClient, times(1))
                .getByOrderId(order.getOrderId());
    }

    @Test
    @DisplayName("상태 조회 결과가 처리 중이면 로컬 자원을 변경하지 않고 미확정으로 유지한다")
    void pay_keepsPending_whenPaymentServerIsPending() {
        String idempotencyKey = "PAYMENT-" + UUID.randomUUID();
        PaymentCreateRequest request = request();

        when(paymentClient.confirm(
                any(PaymentConfirmRequest.class),
                eq("TIMEOUT_BEFORE_APPROVAL")
        )).thenThrow(clientFailure(
                PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED
        ));

        when(paymentClient.getByOrderId(anyString()))
                .thenAnswer(invocation -> new PaymentResponse(
                        invocation.getArgument(0),
                        null,
                        null,
                        PaymentServerStatus.PENDING,
                        AMOUNT,
                        null
                ));

        assertThatThrownBy(() -> paymentService.pay(
                payerId,
                idempotencyKey,
                "TIMEOUT_BEFORE_APPROVAL",
                request
        )).isInstanceOfSatisfying(
                OrderException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(
                        ErrorCode.PAYMENT_RESULT_UNCONFIRMED
                )
        );

        Order order = orderRepository
                .findByPayerIdAndIdempotencyKey(
                        payerId,
                        idempotencyKey
                )
                .orElseThrow();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(order.getApprovalResult()).isEqualTo(PaymentApprovalResult.UNKNOWN);
        assertThat(order.getFailureCode()).isEqualTo(OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED);

        assertThat(readProductQuantity()).isEqualTo(10);
        assertThat(readProductSales()).isZero();
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE);
        assertThat(giftHistoryRepository.findByOrderRecordId(order.getId())).isEmpty();
    }

    @Test
    @DisplayName("결제 취소는 주문·선물·크레딧·재고·판매량을 한 번만 보상한다")
    void cancel_compensatesLocalResourcesOnlyOnce() {
        String paymentIdempotencyKey =
                "PAYMENT-" + UUID.randomUUID();
        String cancellationIdempotencyKey =
                "CANCEL-" + UUID.randomUUID();

        stubApproval();

        PaymentResult payment = paymentService.pay(
                payerId,
                paymentIdempotencyKey,
                "APPROVE",
                request()
        );

        Order order = orderRepository
                .findByOrderId(payment.completion().orderId())
                .orElseThrow();
        LocalDateTime canceledAt = LocalDateTime.of(
                2026,
                10,
                15,
                12,
                0
        );

        stubCancellation(
                order,
                cancellationIdempotencyKey,
                canceledAt
        );

        PaymentCancellationResult first =
                paymentCancellationService.cancel(
                        payerId,
                        order.getOrderId(),
                        cancellationIdempotencyKey,
                        CancellationSource.USER
                );
        PaymentCancellationResult second =
                paymentCancellationService.cancel(
                        payerId,
                        order.getOrderId(),
                        cancellationIdempotencyKey,
                        CancellationSource.USER
                );

        Order canceledOrder = orderRepository
                .findById(order.getId())
                .orElseThrow();

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(canceledOrder.getStatus())
                .isEqualTo(OrderStatus.CANCELED);
        assertThat(readProductQuantity()).isEqualTo(10);
        assertThat(readProductSales()).isZero();
        assertThat(readBalance()).isEqualTo(INITIAL_BALANCE);

        assertThat(giftHistoryRepository
                .findByOrderRecordId(order.getId()))
                .get()
                .extracting(gift -> gift.getStatus())
                .isEqualTo(GiftStatus.CANCELED);

        assertThat(creditTransactionRepository
                .findByDeduplicationKey(
                        "PAYMENT_REFUND:" + order.getId()
                ))
                .get()
                .satisfies(refund -> {
                    assertThat(refund.getType())
                            .isEqualTo(
                                    CreditTransactionType.PAYMENT_REFUND
                            );
                    assertThat(refund.getAmount())
                            .isEqualTo(AMOUNT);
                    assertThat(refund.getBalanceAfter())
                            .isEqualTo(INITIAL_BALANCE);
                });

        assertThat(paymentCancellationRepository
                .findByRequesterIdAndIdempotencyKey(
                        payerId,
                        cancellationIdempotencyKey
                ))
                .get()
                .extracting(cancellation -> cancellation.getStatus())
                .isEqualTo(PaymentCancellationStatus.COMPLETED);

        verify(paymentClient, times(1)).cancel(
                order.getPaymentKey(),
                cancellationIdempotencyKey
        );
    }

    @Test
    @DisplayName("취소 원장 저장 실패 시 로컬 보상 전체를 롤백한다")
    void cancel_rollsBackLocalCompensation_whenLedgerSaveFails() {
        String paymentIdempotencyKey =
                "PAYMENT-" + UUID.randomUUID();
        String cancellationIdempotencyKey =
                "CANCEL-" + UUID.randomUUID();

        stubApproval();

        PaymentResult payment = paymentService.pay(
                payerId,
                paymentIdempotencyKey,
                "APPROVE",
                request()
        );

        Order order = orderRepository
                .findByOrderId(payment.completion().orderId())
                .orElseThrow();
        Long giftId = payment.completion().giftId();

        transaction.executeWithoutResult(status ->
                creditTransactionRepository.saveAndFlush(
                        new CreditTransaction(
                                payerId,
                                CreditTransactionType.PAYMENT_REFUND,
                                AMOUNT,
                                INITIAL_BALANCE - AMOUNT,
                                order.getId(),
                                giftId,
                                null,
                                "PAYMENT_REFUND:" + order.getId()
                        )
                )
        );

        stubCancellation(
                order,
                cancellationIdempotencyKey,
                LocalDateTime.of(2026, 10, 15, 12, 0)
        );

        assertThatThrownBy(() ->
                paymentCancellationService.cancel(
                        payerId,
                        order.getOrderId(),
                        cancellationIdempotencyKey,
                        CancellationSource.USER
                )
        ).isInstanceOf(DataIntegrityViolationException.class);

        Order completedOrder = orderRepository
                .findById(order.getId())
                .orElseThrow();

        assertThat(completedOrder.getStatus())
                .isEqualTo(OrderStatus.COMPLETED);
        assertThat(readProductQuantity()).isEqualTo(8);
        assertThat(readProductSales()).isEqualTo(2);
        assertThat(readBalance())
                .isEqualTo(INITIAL_BALANCE - AMOUNT);
        assertThat(giftHistoryRepository
                .findByOrderRecordId(order.getId()))
                .get()
                .extracting(gift -> gift.getStatus())
                .isEqualTo(GiftStatus.WAITING_FOR_ACCEPTANCE);

        assertThat(paymentCancellationRepository
                .findByRequesterIdAndIdempotencyKey(
                        payerId,
                        cancellationIdempotencyKey
                ))
                .get()
                .satisfies(cancellation -> {
                    assertThat(cancellation.getStatus())
                            .isEqualTo(
                                    PaymentCancellationStatus.PENDING
                            );
                    assertThat(cancellation.getFailureCode())
                            .isEqualTo(
                                    PaymentCancellationFailureCode
                                            .LOCAL_COMPENSATION_FAILED
                            );
                });
    }

    private void stubApproval() {
        when(paymentClient.confirm(
                any(PaymentConfirmRequest.class),
                eq("APPROVE")
        )).thenAnswer(invocation -> {
            PaymentConfirmRequest request =
                    invocation.getArgument(0);

            return new PaymentResponse(
                    request.orderId(),
                    UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(),
                    PaymentServerStatus.COMPLETED,
                    request.amount(),
                    LocalDateTime.of(
                            2026,
                            10,
                            13,
                            14,
                            30
                    )
            );
        });
    }

    private void stubCancellation(
            Order order,
            String idempotencyKey,
            LocalDateTime canceledAt
    ) {
        when(paymentClient.cancel(
                order.getPaymentKey(),
                idempotencyKey
        )).thenReturn(new PaymentCancelResponse(
                order.getOrderId(),
                order.getPaymentKey(),
                UUID.randomUUID().toString(),
                PaymentServerStatus.CANCELED,
                order.getAmount(),
                canceledAt
        ));
    }

    private PaymentCreateRequest request() {
        return new PaymentCreateRequest(
                productId,
                recipientId,
                QUANTITY,
                UNIT_PRICE
        );
    }

    private int readProductQuantity() {
        return jdbcTemplate.queryForObject(
                "select quantity from products where id = ?",
                Integer.class,
                productId
        );
    }

    private int readProductSales() {
        return jdbcTemplate.queryForObject(
                "select sales from products where id = ?",
                Integer.class,
                productId
        );
    }

    private long readBalance() {
        return jdbcTemplate.queryForObject(
                """
                select balance
                from credit_accounts
                where user_id = ?
                """,
                Long.class,
                payerId
        );
    }

    private PaymentResponse completedResponse(String orderId, Long amount) {
        return new PaymentResponse(
                orderId,
                "7d9f6d7e-a34c-4bbc-97d6-33a2b43c183a",
                "98f7a12c-9952-40b6-9142-a07b7c4b13fe",
                PaymentServerStatus.COMPLETED,
                amount,
                LocalDateTime.of(
                        2026,
                        10,
                        14,
                        14,
                        30
                )
        );
    }

    private PaymentClientException clientFailure(
            PaymentClientFailure failure
    ) {
        return new PaymentClientException(
                failure,
                "Payment Server 테스트 오류",
                (String) null
        );
    }
}
