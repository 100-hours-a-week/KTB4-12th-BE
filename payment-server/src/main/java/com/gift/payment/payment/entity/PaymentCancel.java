package com.gift.payment.payment.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.payment.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "payment_cancels",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_cancels_payment_idempotency",
                        columnNames = {"payment_id", "idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uk_payment_cancels_transaction_key",
                        columnNames = "transaction_key"
                )
        },
        indexes = @Index(
                name = "idx_payment_cancels_payment_created",
                columnList = "payment_id, created_at, id"
        ),
        check = {
                @CheckConstraint(
                        name = "chk_payment_cancels_status",
                        constraint = "status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED')"
                ),
                @CheckConstraint(
                        name = "chk_payment_cancels_amount_positive",
                        constraint = "cancel_amount IS NULL OR cancel_amount > 0"
                ),
                @CheckConstraint(
                        name = "chk_payment_cancels_status_fields",
                        constraint = """
                                (
                                    status = 'PENDING'
                                    AND transaction_key IS NULL
                                    AND cancel_amount IS NULL
                                    AND failure_code IS NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'COMPLETED'
                                    AND transaction_key IS NOT NULL
                                    AND cancel_amount IS NOT NULL
                                    AND failure_code IS NULL
                                    AND canceled_at IS NOT NULL
                                )
                                OR (
                                    status IN ('DECLINED', 'FAILED')
                                    AND transaction_key IS NULL
                                    AND cancel_amount IS NULL
                                    AND failure_code IS NOT NULL
                                    AND canceled_at IS NULL
                                )
                                """
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancel extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "payment_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_payment_cancels_payment")
    )
    private Payment payment;

    @Column(
            name = "idempotency_key",
            nullable = false,
            updatable = false,
            length = 100
    )
    private String idempotencyKey;

    @Column(
            name = "request_hash",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(64)"
    )
    private String requestHash;

    @Column(name = "transaction_key", length = 36)
    private String transactionKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'PENDING'")
    private PaymentCancelStatus status = PaymentCancelStatus.PENDING;

    @Column(name = "cancel_amount")
    private Long cancelAmount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_code", length = 60)
    private PaymentCancelFailureCode failureCode;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public PaymentCancel(Payment payment, String idempotencyKey, String requestHash) {

        this.payment = Objects.requireNonNull(payment, "원결제는 필수입니다.");
        validateText(idempotencyKey, 100, "멱등성 키");
        validateHash(requestHash);

        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
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
