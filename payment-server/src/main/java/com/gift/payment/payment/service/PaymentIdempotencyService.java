package com.gift.payment.payment.service;

import java.sql.SQLException;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.repository.PaymentRepository;
import com.gift.payment.payment.support.PaymentRequestHasher;

@Service
@RequiredArgsConstructor
public class PaymentIdempotencyService {

    private static final String ORDER_ID_CONSTRAINT = "uk_payments_order_id";
    private static final String INTEGRITY_SQL_STATE = "23000";
    private static final int DUPLICATE_ENTRY_CODE = 1062;

    private final PaymentRepository paymentRepository;
    private final PaymentTransactionService transactionService;
    private final PaymentRequestHasher requestHasher;

    public PaymentStartResult start(String orderId, Long amount) {

        validate(orderId, amount);

        String requestHash = requestHasher.hash(orderId, amount);

        Optional<Payment> existing = paymentRepository.findByOrderId(orderId);

        if (existing.isPresent()) {
            return existing(existing.get(), requestHash);
        }

        try {
            Payment payment = transactionService.create(orderId, requestHash, amount);

            return PaymentStartResult.created(payment);
        } catch (DataIntegrityViolationException exception) {
            if (!isOrderIdDuplicate(exception)) {
                throw exception;
            }

            Payment payment = paymentRepository
                    .findByOrderId(orderId)
                    .orElseThrow(() -> exception);

            return existing(payment, requestHash);
        }
    }

    private PaymentStartResult existing(Payment payment, String requestHash) {

        if (!payment.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.ORDER_ID_CONFLICT);
        }

        return PaymentStartResult.existing(payment);
    }

    private boolean isOrderIdDuplicate(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SQLException sqlException
                    && INTEGRITY_SQL_STATE.equals(sqlException.getSQLState())
                    && sqlException.getErrorCode() == DUPLICATE_ENTRY_CODE
                    && containsOrderIdConstraint(sqlException.getMessage())) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }

    private boolean containsOrderIdConstraint(String message) {
        return message != null
                && message.toLowerCase(Locale.ROOT)
                .contains(ORDER_ID_CONSTRAINT);
    }

    private void validate(String orderId, Long amount) {
        validateOrderId(orderId);

        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 양수여야 합니다.");
        }
    }

    private void validateOrderId(String orderId) {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("주문번호 형식을 확인해 주세요.");
        }

        try {
            UUID.fromString(orderId);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("주문번호 형식을 확인해 주세요.", exception);
        }
    }
}
