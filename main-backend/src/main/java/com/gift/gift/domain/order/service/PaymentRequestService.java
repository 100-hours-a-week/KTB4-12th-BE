package com.gift.gift.domain.order.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.support.PaymentRequestHasher;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class PaymentRequestService {

    private static final String IDEMPOTENCY_CONSTRAINT = "uk_orders_payer_idempotency";
    private static final String INTEGRITY_SQL_STATE = "23000";
    private static final int DUPLICATE_ENTRY_CODE = 1062;
    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final OrderRepository orderRepository;
    private final PaymentValidator paymentValidator;
    private final OrderTransactionService transactionService;
    private final PaymentRequestHasher requestHasher;

    public PaymentStartResult start(Long payerId, String idempotencyKey, PaymentCreateRequest request) {

        validateInput(payerId, idempotencyKey, request);

        String requestHash = requestHasher.hash(request);

        Optional<Order> existing = orderRepository
                .findByPayerIdAndIdempotencyKey(payerId, idempotencyKey);

        if (existing.isPresent()) {
            return existing(existing.get(), requestHash);
        }

        PaymentValidation validation = paymentValidator.validate(payerId, request);

        try {
            Order order = transactionService.create(
                    payerId,
                    idempotencyKey,
                    requestHash,
                    request,
                    validation
            );

            return PaymentStartResult.created(order);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyDuplicate(exception)) {
                throw exception;
            }

            Order order = orderRepository
                    .findByPayerIdAndIdempotencyKey(payerId, idempotencyKey)
                    .orElseThrow(() -> exception);

            return existing(order, requestHash);
        }
    }

    private PaymentStartResult existing(Order order, String requestHash) {

        if (!order.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        return PaymentStartResult.existing(order);
    }

    private boolean isIdempotencyDuplicate(Throwable throwable) {

        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && INTEGRITY_SQL_STATE.equals(sqlException.getSQLState())
                    && sqlException.getErrorCode() == DUPLICATE_ENTRY_CODE
                    && containsIdempotencyConstraint(sqlException.getMessage())) {

                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private boolean containsIdempotencyConstraint(String message) {

        return message != null
                && message.toLowerCase(Locale.ROOT).contains(IDEMPOTENCY_CONSTRAINT);
    }

    private void validateInput(Long payerId, String idempotencyKey, PaymentCreateRequest request) {

        if (payerId == null || payerId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {

            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }

        if (request == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
