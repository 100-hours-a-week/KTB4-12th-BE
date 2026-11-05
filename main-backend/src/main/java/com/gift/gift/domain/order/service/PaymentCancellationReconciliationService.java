package com.gift.gift.domain.order.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.entity.PaymentCancellationFailureCode;
import com.gift.gift.domain.order.entity.PaymentCancellationStatus;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerCancellationStatus;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;

@Slf4j
@Service
public class PaymentCancellationReconciliationService {

    private static final int MAX_RECOVERY_CANCEL_POSTS = 3;

    private final PaymentCancellationRepository cancellationRepository;
    private final OrderRepository orderRepository;
    private final PaymentClient paymentClient;
    private final PaymentCancellationTransactionService transactionService;
    private final PaymentCancellationCompensationService compensationService;
    private final Clock clock;
    private final int batchSize;
    private final Duration timeout;

    public PaymentCancellationReconciliationService(
            PaymentCancellationRepository cancellationRepository,
            OrderRepository orderRepository,
            PaymentClient paymentClient,
            PaymentCancellationTransactionService transactionService,
            PaymentCancellationCompensationService compensationService,
            Clock clock,
            @Value("${app.payment.reconciliation.batch-size:100}") int batchSize,
            @Value("${app.payment.reconciliation.timeout:PT10M}") Duration timeout
    ) {
        this.cancellationRepository = cancellationRepository;
        this.orderRepository = orderRepository;
        this.paymentClient = paymentClient;
        this.transactionService = transactionService;
        this.compensationService = compensationService;
        this.clock = clock;
        this.batchSize = batchSize;
        this.timeout = timeout;
    }

    public void reconcilePendingCancellations() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<PaymentCancellation> candidates =
                cancellationRepository.findDueReconciliationCandidates(
                        CancellationSource.USER,
                        PaymentCancellationStatus.PENDING,
                        PaymentCancellationFailureCode.CANCELLATION_RECONCILIATION_REQUIRED,
                        now,
                        PageRequest.of(0, batchSize)
                );

        for (PaymentCancellation cancellation : candidates) {
            try {
                reconcile(cancellation, now);
            } catch (RuntimeException exception) {
                log.error(
                        "취소 결과 재조정에 실패했습니다. cancellationId={}",
                        cancellation.getId(),
                        exception
                );
            }
        }
    }

    private void reconcile(PaymentCancellation cancellation, LocalDateTime now) {
        Order order = orderRepository.findById(cancellation.getOrderRecordId())
                .orElseThrow(() -> new IllegalStateException(
                        "취소 대상 주문을 찾을 수 없습니다."
                ));

        if (hasPartialRemoteResult(cancellation)) {
            requireReview(cancellation, "저장된 원격 취소 결과가 불완전합니다.");
            return;
        }

        if (hasRemoteResult(cancellation)) {
            resumeLocalCompensation(cancellation, order, now);
            return;
        }

        boolean expired = isExpired(cancellation, now);
        PaymentCancelResponse response;

        try {
            response = paymentClient.getCancellation(
                    order.getPaymentKey(),
                    cancellation.getIdempotencyKey()
            );
        } catch (PaymentClientException exception) {
            if (exception.failure() == PaymentClientFailure.PAYMENT_NOT_FOUND) {
                handleMissingCancellation(cancellation, order, now, expired);
                return;
            }

            scheduleOrRequireReview(
                    cancellation,
                    now,
                    expired,
                    exception.failure() == PaymentClientFailure.PAYMENT_SERVER_UNAVAILABLE
                            ? PaymentCancellationFailureCode.PAYMENT_SERVER_UNAVAILABLE
                            : PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
            );
            return;
        }

        if (response == null || !matchesCancellationIdentity(order, response)) {
            requireReview(cancellation, "취소 상태 조회 응답의 주문 정보가 일치하지 않습니다.");
            return;
        }

        if (isCompletedCancellation(order, response)) {
            saveAndCompensate(cancellation, order, response, now);
            return;
        }

        if (response.cancellationStatus() == PaymentServerCancellationStatus.DECLINED) {
            transactionService.markDeclined(cancellation.getId());
            return;
        }

        if (response.cancellationStatus() == PaymentServerCancellationStatus.PENDING) {
            if (response.status() != PaymentServerStatus.COMPLETED) {
                requireReview(cancellation, "취소 원장과 원결제 상태가 일치하지 않습니다.");
                return;
            }

            if (expired) {
                requireReview(cancellation, "복구 기한 만료 후에도 취소 결과가 미확정입니다.");
                return;
            }

            postCancellationIfAvailable(cancellation, order, now);
            return;
        }

        scheduleOrRequireReview(
                cancellation,
                now,
                expired,
                PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
        );
    }

    private void handleMissingCancellation(
            PaymentCancellation cancellation,
            Order order,
            LocalDateTime now,
            boolean expired
    ) {
        PaymentResponse originalPayment;

        try {
            originalPayment = paymentClient.getByOrderId(order.getOrderId());
        } catch (RuntimeException exception) {
            scheduleOrRequireReview(
                    cancellation,
                    now,
                    expired,
                    PaymentCancellationFailureCode.PAYMENT_SERVER_UNAVAILABLE
            );
            return;
        }

        if (originalPayment == null
                || !order.getOrderId().equals(originalPayment.orderId())
                || !order.getAmount().equals(originalPayment.amount())) {
            requireReview(cancellation, "원결제 상태 조회 결과의 주문 정보가 일치하지 않습니다.");
            return;
        }

        if (originalPayment.status() != PaymentServerStatus.COMPLETED) {
            scheduleOrRequireReview(
                    cancellation,
                    now,
                    expired,
                    PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
            );
            return;
        }

        if (expired) {
            requireReview(cancellation, "복구 기한 만료 후 취소 원장이 확인되지 않았습니다.");
            return;
        }

        postCancellationIfAvailable(cancellation, order, now);
    }

    private void postCancellationIfAvailable(
            PaymentCancellation cancellation,
            Order order,
            LocalDateTime now
    ) {
        if (isExpired(cancellation, LocalDateTime.now(clock))) {
            requireReview(cancellation, "취소 복구 기한이 만료되어 POST를 중단했습니다.");
            return;
        }

        boolean reserved = transactionService.reserveRecoveryCancelPost(
                cancellation.getId(),
                MAX_RECOVERY_CANCEL_POSTS
        );

        if (!reserved) {
            scheduleNext(
                    cancellation,
                    now,
                    PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
            );
            return;
        }

        try {
            PaymentCancelResponse response = paymentClient.cancel(
                    order.getPaymentKey(),
                    cancellation.getIdempotencyKey()
            );

            if (response == null || !matchesCancellationIdentity(order, response)) {
                requireReview(cancellation, "취소 POST 응답의 주문 정보가 일치하지 않습니다.");
                return;
            }

            if (isCompletedCancellation(order, response)) {
                saveAndCompensate(cancellation, order, response, now);
                return;
            }

            if (response.cancellationStatus() == PaymentServerCancellationStatus.DECLINED) {
                transactionService.markDeclined(cancellation.getId());
                return;
            }

            scheduleOrRequireReview(
                    cancellation,
                    LocalDateTime.now(clock),
                    isExpired(cancellation, LocalDateTime.now(clock)),
                    PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
            );
        } catch (PaymentClientException exception) {
            if (exception.failure() == PaymentClientFailure.CANCELLATION_DECLINED) {
                transactionService.markDeclined(cancellation.getId());
                return;
            }

            scheduleOrRequireReview(
                    cancellation,
                    LocalDateTime.now(clock),
                    isExpired(cancellation, LocalDateTime.now(clock)),
                    PaymentCancellationFailureCode.CANCELLATION_RESULT_UNCONFIRMED
            );
        }
    }

    private void saveAndCompensate(
            PaymentCancellation cancellation,
            Order order,
            PaymentCancelResponse response,
            LocalDateTime now
    ) {
        if (!isCompletedCancellation(order, response)) {
            requireReview(cancellation, "확정 취소 응답의 필수 값이 올바르지 않습니다.");
            return;
        }

        transactionService.recordPaymentServerCancellationResult(
                cancellation.getId(),
                response.transactionKey(),
                response.cancelAmount(),
                response.canceledAt()
        );

        resumeLocalCompensation(cancellation, order, now);
    }

    private void resumeLocalCompensation(
            PaymentCancellation cancellation,
            Order order,
            LocalDateTime now
    ) {
        PaymentCancellationApproval approval = new PaymentCancellationApproval(
                cancellation,
                order,
                cancellation.getCancelTransactionKey(),
                cancellation.getCancelAmount(),
                cancellation.getCanceledAt(),
                false
        );

        try {
            compensationService.compensate(approval);
        } catch (RuntimeException exception) {
            LocalDateTime current = LocalDateTime.now(clock);
            scheduleOrRequireReview(
                    cancellation,
                    current,
                    isExpired(cancellation, current),
                    PaymentCancellationFailureCode.LOCAL_COMPENSATION_FAILED
            );
            log.error(
                    "원격 취소 확인 후 로컬 보상에 실패했습니다. cancellationId={}",
                    cancellation.getId(),
                    exception
            );
        }
    }

    private boolean matchesCancellationIdentity(
            Order order,
            PaymentCancelResponse response
    ) {
        return order.getOrderId().equals(response.orderId())
                && order.getPaymentKey().equals(response.paymentKey());
    }

    private boolean isCompletedCancellation(
            Order order,
            PaymentCancelResponse response
    ) {
        return response.cancellationStatus() == PaymentServerCancellationStatus.COMPLETED
                && response.status() == PaymentServerStatus.CANCELED
                && order.getAmount().equals(response.cancelAmount())
                && hasText(response.transactionKey())
                && response.canceledAt() != null;
    }

    private boolean hasRemoteResult(PaymentCancellation cancellation) {
        return cancellation.getCancelTransactionKey() != null
                && cancellation.getCancelAmount() != null
                && cancellation.getCanceledAt() != null;
    }

    private boolean hasPartialRemoteResult(PaymentCancellation cancellation) {
        boolean hasAny = cancellation.getCancelTransactionKey() != null
                || cancellation.getCancelAmount() != null
                || cancellation.getCanceledAt() != null;

        return hasAny && !hasRemoteResult(cancellation);
    }

    private boolean isExpired(PaymentCancellation cancellation, LocalDateTime now) {
        LocalDateTime startedAt = cancellation.getReconciliationStartedAt() != null
                ? cancellation.getReconciliationStartedAt()
                : cancellation.getCreatedAt();

        return startedAt != null && !startedAt.plus(timeout).isAfter(now);
    }

    private void scheduleOrRequireReview(
            PaymentCancellation cancellation,
            LocalDateTime now,
            boolean expired,
            PaymentCancellationFailureCode failureCode
    ) {
        if (expired) {
            requireReview(cancellation, "취소 복구 기한 만료 후 결과가 미확정입니다.");
            return;
        }

        scheduleNext(cancellation, now, failureCode);
    }

    private void scheduleNext(
            PaymentCancellation cancellation,
            LocalDateTime now,
            PaymentCancellationFailureCode failureCode
    ) {
        transactionService.scheduleNext(
                cancellation.getId(),
                failureCode,
                now.plus(nextDelay(cancellation.getReconciliationAttempts()))
        );
    }

    private Duration nextDelay(int completedReconciliationAttempts) {
        return switch (Math.min(completedReconciliationAttempts, 3)) {
            case 0 -> Duration.ofSeconds(15);
            case 1 -> Duration.ofSeconds(30);
            default -> Duration.ofSeconds(60);
        };
    }

    private void requireReview(PaymentCancellation cancellation, String reason) {
        transactionService.markReconciliationRequired(cancellation.getId());
        log.error(
                "{} cancellationId={}",
                reason,
                cancellation.getId()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
