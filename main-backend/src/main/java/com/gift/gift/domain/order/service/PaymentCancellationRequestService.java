package com.gift.gift.domain.order.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentApprovalResult;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.repository.PaymentCancellationRepository;
import com.gift.gift.domain.order.support.PaymentCancellationRequestHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class PaymentCancellationRequestService {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_payment_cancellations_requester_idempotency";

    private static final String INTEGRITY_SQL_STATE = "23000";
    private static final int DUPLICATE_ENTRY_CODE = 1062;

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9:_-]{1,100}$");

    private static final String GIFT_EXPIRATION_PREFIX = "GIFT_EXPIRATION:";
    private static final String LOCAL_PROCESSING_FAILURE_PREFIX = "LOCAL_PROCESSING_FAILURE:";

    private final OrderRepository orderRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final PaymentCancellationRepository cancellationRepository;
    private final PaymentCancellationTransactionService transactionService;
    private final PaymentCancellationRequestHasher requestHasher;

    public PaymentCancellationStartResult start(
            Long requesterId,
            String orderId,
            String idempotencyKey,
            CancellationSource source
    ) {
        validateInput(requesterId, orderId, idempotencyKey, source);

        String requestHash = requestHasher.hash(orderId);

        Optional<PaymentCancellation> existing =
                cancellationRepository
                        .findByRequesterIdAndIdempotencyKey(requesterId, idempotencyKey);

        if (existing.isPresent()) {
            return existing(
                    existing.get(),
                    requesterId,
                    requestHash,
                    source
            );
        }

        Order order = orderRepository
                .findByOrderIdAndPayerId(orderId, requesterId)
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYMENT_NOT_FOUND));

        validateCancelable(order, source);

        try {
            PaymentCancellation cancellation =
                    transactionService.create(
                            order,
                            requesterId,
                            source,
                            idempotencyKey,
                            requestHash
                    );

            return PaymentCancellationStartResult.created(cancellation, order);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyDuplicate(exception)) {
                throw exception;
            }

            PaymentCancellation cancellation =
                    cancellationRepository
                            .findByRequesterIdAndIdempotencyKey(requesterId, idempotencyKey)
                            .orElseThrow(() -> exception);

            return existing(cancellation, requesterId, requestHash, source);
        }
    }

    private PaymentCancellationStartResult existing(
            PaymentCancellation cancellation,
            Long requesterId,
            String requestHash,
            CancellationSource source
    ) {
        if (!cancellation.getRequestHash().equals(requestHash)
                || cancellation.getCancellationSource() != source) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        Order order = orderRepository
                .findById(cancellation.getOrderRecordId())
                .filter(found -> found.getPayerId().equals(requesterId))
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYMENT_NOT_FOUND));

        return PaymentCancellationStartResult.existing(cancellation, order);
    }

    private void validateCancelable(Order order, CancellationSource source) {
        if (source == CancellationSource.LOCAL_PROCESSING_FAILURE) {
            if (order.getStatus() != OrderStatus.PENDING
                    || order.getApprovalResult() != PaymentApprovalResult.APPROVED
                    || !hasText(order.getPaymentKey())) {
                throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
            }

            return;
        }

        if (order.getStatus() != OrderStatus.COMPLETED
                || !hasText(order.getPaymentKey())) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        GiftHistory gift = giftHistoryRepository
                .findByOrderRecordId(order.getId())
                .orElseThrow(() -> new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE));

        if (gift.getStatus() != GiftStatus.WAITING_FOR_ACCEPTANCE
                || gift.getDeletedAt() != null) {
            throw new OrderException(OrderErrorCode.PAYMENT_NOT_CANCELABLE);
        }
    }

    private void validateInput(
            Long requesterId,
            String orderId,
            String idempotencyKey,
            CancellationSource source
    ) {
        if (requesterId == null || requesterId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        try {
            UUID.fromString(orderId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (source == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()
                || (source == CancellationSource.USER
                && (idempotencyKey.startsWith(GIFT_EXPIRATION_PREFIX)
                || idempotencyKey.startsWith(LOCAL_PROCESSING_FAILURE_PREFIX)))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }

    private boolean isIdempotencyDuplicate(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && INTEGRITY_SQL_STATE.equals(sqlException.getSQLState())
                    && sqlException.getErrorCode() == DUPLICATE_ENTRY_CODE
                    && containsConstraint(sqlException.getMessage())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private boolean containsConstraint(String message) {
        return message != null
                && message.toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_CONSTRAINT);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
