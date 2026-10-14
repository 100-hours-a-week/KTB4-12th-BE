package com.gift.payment.payment.service;

import java.util.Optional;
import java.util.regex.Pattern;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.entity.PaymentCancelStatus;
import com.gift.payment.payment.entity.PaymentStatus;
import com.gift.payment.payment.repository.PaymentCancelRepository;
import com.gift.payment.payment.repository.PaymentRepository;
import com.gift.payment.payment.support.PaymentCancelRequestHasher;

@Service
@RequiredArgsConstructor
public class PaymentCancelService {

    private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,100}$");

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;
    private final PaymentCancelTransactionService transactionService;
    private final PaymentCancelRequestHasher requestHasher;

    public PaymentCancel cancel(String paymentKey, String idempotencyKey) {

        validateIdempotencyKey(idempotencyKey);

        Payment payment = paymentRepository
                .findByPaymentKey(paymentKey)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PAYMENT_NOT_FOUND
                ));

        String requestHash = requestHasher.hash(payment.getOrderId());

        Optional<PaymentCancel> existing =
                paymentCancelRepository
                        .findByPayment_IdAndIdempotencyKey(
                                payment.getId(),
                                idempotencyKey
                        );

        if (existing.isPresent()) {
            return existing(existing.get(), requestHash);
        }

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_CANCELABLE);
        }

        try {
            return transactionService.create(
                    payment.getId(),
                    idempotencyKey,
                    requestHash
            );
        } catch (BusinessException exception) {
            if (exception.errorCode()
                    != ErrorCode.PAYMENT_NOT_CANCELABLE) {
                throw exception;
            }

            return recoverConcurrentRequest(
                    payment.getId(),
                    idempotencyKey,
                    requestHash,
                    exception
            );
        }
    }

    private PaymentCancel existing(PaymentCancel cancellation, String requestHash) {

        if (!cancellation.getRequestHash().equals(requestHash)) {
            throw new BusinessException(
                    ErrorCode.IDEMPOTENCY_KEY_CONFLICT
            );
        }

        return switch (cancellation.getStatus()) {
            case COMPLETED -> cancellation;

            case PENDING -> transactionService.resume(cancellation.getId());

            case DECLINED -> throw new BusinessException(ErrorCode.CANCELLATION_DECLINED);

            case FAILED -> throw new BusinessException(ErrorCode.TEMPORARY_PROCESSING_ERROR);
        };
    }

    private PaymentCancel recoverConcurrentRequest(
            Long paymentId,
            String idempotencyKey,
            String requestHash,
            BusinessException originalException
    ) {
        PaymentCancel cancellation = paymentCancelRepository
                .findByPayment_IdAndIdempotencyKey(paymentId, idempotencyKey)
                .orElseThrow(() -> originalException);

        if (!cancellation.getRequestHash().equals(requestHash)) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }

        if (cancellation.getStatus() == PaymentCancelStatus.COMPLETED) {
            return cancellation;
        }

        throw originalException;
    }

    private void validateIdempotencyKey(String idempotencyKey) {

        if (idempotencyKey == null
                || !IDEMPOTENCY_KEY_PATTERN.matcher(idempotencyKey).matches()) {

            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
    }
}
