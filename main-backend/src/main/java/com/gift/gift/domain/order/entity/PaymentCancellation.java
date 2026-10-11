package com.gift.gift.domain.order.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "payment_cancellations",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_cancellations_requester_idempotency",
                        columnNames = {"requester_id", "idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uk_payment_cancellations_transaction_key",
                        columnNames = "cancel_transaction_key"
                )
        },
        indexes = @Index(
                name = "idx_payment_cancellations_order",
                columnList = "order_record_id, created_at"
        ),
        check = {
                @CheckConstraint(
                        name = "chk_payment_cancellations_source",
                        constraint = "cancellation_source IN ('USER', 'GIFT_EXPIRATION')"
                ),
                @CheckConstraint(
                        name = "chk_payment_cancellations_status",
                        constraint = "status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED')"
                ),
                @CheckConstraint(
                        name = "chk_payment_cancellations_amount_non_negative",
                        constraint = "cancel_amount IS NULL OR cancel_amount >= 0"
                ),
                @CheckConstraint(
                        name = "chk_payment_cancellations_failure_code",
                        constraint = """
                                failure_code IS NULL
                                OR failure_code IN (
                                    'CANCELLATION_DECLINED',
                                    'CANCELLATION_RESULT_UNCONFIRMED',
                                    'PAYMENT_SERVER_UNAVAILABLE',
                                    'LOCAL_COMPENSATION_FAILED'
                                )
                                """
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancellation extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_record_id", nullable = false, updatable = false)
    private Long orderRecordId;

    @Column(name = "requester_id", nullable = false, updatable = false)
    private Long requesterId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cancellation_source", nullable = false, updatable = false, length = 24)
    private CancellationSource cancellationSource;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentCancellationStatus status = PaymentCancellationStatus.PENDING;

    @Column(name = "cancel_transaction_key", length = 36)
    private String cancelTransactionKey;

    @Column(name = "cancel_amount")
    private Long cancelAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_code", length = 60)
    private PaymentCancellationFailureCode failureCode;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public PaymentCancellation(
            Long orderRecordId,
            Long requesterId,
            CancellationSource cancellationSource,
            String idempotencyKey,
            String requestHash
    ) {
        validateId(orderRecordId, "주문 레코드 ID");
        validateId(requesterId, "취소 요청자 ID");
        validateText(idempotencyKey, 100, "멱등성 키");
        validateHash(requestHash);

        this.orderRecordId = orderRecordId;
        this.requesterId = requesterId;
        this.cancellationSource = Objects.requireNonNull(
                cancellationSource,
                "취소 요청 출처는 필수입니다."
        );
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
    }

    private static void validateId(Long value, String fieldName) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(fieldName + "는 양수여야 합니다.");
        }
    }

    private static void validateText(String value, int maxLength, String fieldName) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 형식을 확인해 주세요.");
        }
    }

    private static void validateHash(String requestHash) {
        if (requestHash == null || !requestHash.matches("^[0-9a-fA-F]{64}$")) {
            throw new IllegalArgumentException("요청 해시는 64자리 16진수여야 합니다.");
        }
    }
}
