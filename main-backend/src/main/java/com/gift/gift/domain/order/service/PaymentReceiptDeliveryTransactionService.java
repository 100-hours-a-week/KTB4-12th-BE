package com.gift.gift.domain.order.service;

import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.order.repository.PaymentReceiptDelivery;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryRepository;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryStatus;
import com.gift.gift.domain.order.repository.PaymentReceiptDeliveryType;
import com.gift.gift.domain.order.repository.PaymentReceiptMailData;

@Service
@RequiredArgsConstructor
public class PaymentReceiptDeliveryTransactionService {

    private static final int MAX_AUTOMATIC_ATTEMPTS = 4;

    private final PaymentReceiptDeliveryRepository repository;

    @Transactional
    public Claim claim(PaymentReceiptDeliveryType receiptType, long sourceId, String replayToken) {
        if (sourceId <= 0) {
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        PaymentReceiptDelivery delivery = repository.findForUpdate(receiptType, sourceId).orElse(null);

        if (delivery == null) {
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        if (delivery.status() == PaymentReceiptDeliveryStatus.SENT
                || delivery.status() == PaymentReceiptDeliveryStatus.SKIPPED) {
            return result(Action.ACK, receiptType, sourceId);
        }

        if (delivery.status() == PaymentReceiptDeliveryStatus.SENDING && delivery.leaseActive()) {
            return result(Action.RETRY, receiptType, sourceId);
        }

        if (delivery.status() == PaymentReceiptDeliveryStatus.SENDING && delivery.replayUsedAt() != null) {
            repository.markFailedLocked(
                    receiptType,
                    sourceId,
                    "MANUAL_RESULT_UNKNOWN"
            );
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        boolean manualReplay = replayToken != null;

        if (manualReplay
                && (!Objects.equals(replayToken, delivery.replayToken())
                || delivery.replayUsedAt() != null)) {
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        if (!manualReplay && delivery.status() == PaymentReceiptDeliveryStatus.FAILED) {
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        if (!manualReplay && delivery.autoAttempts() >= MAX_AUTOMATIC_ATTEMPTS) {
            repository.markFailedLocked(
                    receiptType,
                    sourceId,
                    "AUTO_ATTEMPTS_EXHAUSTED"
            );
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        PaymentReceiptMailData mailData = repository.findMailData(receiptType, sourceId)
                .orElse(null);

        if (!isValid(mailData, receiptType)) {
            repository.markFailedLocked(
                    receiptType,
                    sourceId,
                    "RECEIPT_DATA_INVALID"
            );
            return result(Action.DEAD_LETTER, receiptType, sourceId);
        }

        String claimToken = UUID.randomUUID().toString();

        int claimed = manualReplay
                ? repository.claimManual(receiptType, sourceId, claimToken, replayToken)
                : repository.claimAutomatic(receiptType, sourceId, claimToken);

        if (claimed != 1) {
            return result(Action.RETRY, receiptType, sourceId);
        }

        int attempts = manualReplay
                ? delivery.autoAttempts()
                : delivery.autoAttempts() + 1;

        return new Claim(
                Action.SEND,
                receiptType,
                sourceId,
                claimToken,
                mailData,
                manualReplay,
                attempts
        );
    }

    @Transactional
    public Action markAccepted(Claim claim) {
        int updated = repository.markSent(
                claim.receiptType(),
                claim.sourceId(),
                claim.claimToken()
        );

        return updated == 1
                ? Action.ACK
                : resultAfterLostClaim(claim.receiptType(), claim.sourceId());
    }

    @Transactional
    public Action recordFailure(Claim claim, String errorCode, boolean permanent) {
        boolean retryable = !permanent
                && !claim.manualReplay()
                && claim.autoAttempts() < MAX_AUTOMATIC_ATTEMPTS;

        int updated = repository.recordFailure(
                claim.receiptType(),
                claim.sourceId(),
                claim.claimToken(),
                errorCode,
                retryable
        );

        if (updated == 0) {
            return resultAfterLostClaim(claim.receiptType(), claim.sourceId());
        }

        return retryable ? Action.RETRY : Action.DEAD_LETTER;
    }

    private Action resultAfterLostClaim(PaymentReceiptDeliveryType receiptType, long sourceId) {
        PaymentReceiptDeliveryStatus status = repository.findStatus(receiptType, sourceId).orElse(null);

        if (status == PaymentReceiptDeliveryStatus.SENT
                || status == PaymentReceiptDeliveryStatus.SKIPPED) {
            return Action.ACK;
        }

        if (status == PaymentReceiptDeliveryStatus.FAILED) {
            return Action.DEAD_LETTER;
        }

        return Action.RETRY;
    }

    private boolean isValid(PaymentReceiptMailData mailData, PaymentReceiptDeliveryType receiptType) {
        if (mailData == null
                || mailData.recipientEmail() == null
                || mailData.recipientEmail().isBlank()
                || mailData.orderId() == null
                || mailData.orderId().isBlank()
                || mailData.productName() == null
                || mailData.quantity() < 1
                || mailData.amount() <= 0
                || mailData.balanceAfter() < 0
                || mailData.paymentCompletedAt() == null) {
            return false;
        }

        return receiptType != PaymentReceiptDeliveryType.CANCELLATION || mailData.canceledAt() != null;
    }

    private Claim result(Action action, PaymentReceiptDeliveryType receiptType, long sourceId) {
        return new Claim(action, receiptType, sourceId, null, null, false, 0);
    }

    public enum Action {
        SEND,
        ACK,
        RETRY,
        DEAD_LETTER
    }

    public record Claim(
            Action action,
            PaymentReceiptDeliveryType receiptType,
            long sourceId,
            String claimToken,
            PaymentReceiptMailData mailData,
            boolean manualReplay,
            int autoAttempts
    ) {
    }
}
