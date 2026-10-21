package com.gift.payment.payment.service;

import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.payment.global.exception.BusinessException;
import com.gift.payment.global.exception.ErrorCode;
import com.gift.payment.payment.entity.Payment;
import com.gift.payment.payment.entity.PaymentCancel;
import com.gift.payment.payment.entity.PaymentStatus;
import com.gift.payment.payment.repository.PaymentCancelRepository;
import com.gift.payment.payment.repository.PaymentRepository;

@Service
@RequiredArgsConstructor
public class PaymentCancelTransactionService {

    private final PaymentRepository paymentRepository;
    private final PaymentCancelRepository paymentCancelRepository;

    @Transactional
    public PaymentCancel create(Long paymentId, String idempotencyKey, String requestHash) {

        LocalDateTime canceledAt = LocalDateTime.now();

        cancelPayment(paymentId, canceledAt);

        Payment payment = getPayment(paymentId);
        PaymentCancel cancellation = new PaymentCancel(payment, idempotencyKey, requestHash);

        cancellation.complete(UUID.randomUUID().toString(), payment.getAmount(), canceledAt);

        return paymentCancelRepository.saveAndFlush(cancellation);
    }

    @Transactional
    public PaymentCancel resume(Long cancellationId) {
        PaymentCancel cancellation = paymentCancelRepository
                .findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException(
                        "처리 중인 결제 취소를 찾을 수 없습니다."
                ));

        Long paymentId = cancellation.getPayment().getId();
        LocalDateTime canceledAt = LocalDateTime.now();

        cancelPayment(paymentId, canceledAt);

        PaymentCancel managedCancellation = paymentCancelRepository
                .findById(cancellationId)
                .orElseThrow(() -> new IllegalStateException(
                        "처리 중인 결제 취소를 찾을 수 없습니다."
                ));

        Payment payment = getPayment(paymentId);

        managedCancellation.complete(
                UUID.randomUUID().toString(),
                payment.getAmount(),
                canceledAt
        );

        return managedCancellation;
    }

    private void cancelPayment(Long paymentId, LocalDateTime canceledAt) {

        int updatedCount = paymentRepository.cancel(
                paymentId,
                PaymentStatus.COMPLETED,
                PaymentStatus.CANCELED,
                canceledAt
        );

        if (updatedCount != 1) {
            throw new BusinessException(ErrorCode.PAYMENT_NOT_CANCELABLE);
        }
    }

    private Payment getPayment(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PAYMENT_NOT_FOUND
                ));
    }
}
