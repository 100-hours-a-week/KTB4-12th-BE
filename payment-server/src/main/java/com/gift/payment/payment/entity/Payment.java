package com.gift.payment.payment.entity;

import java.time.LocalDateTime;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
        name = "payments",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payments_order_id",
                        columnNames = "order_id"
                ),
                @UniqueConstraint(
                        name = "uk_payments_payment_key",
                        columnNames = "payment_key"
                ),
                @UniqueConstraint(
                        name = "uk_payments_approved_transaction_key",
                        columnNames = "approved_transaction_key"
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_payments_amount_positive",
                        constraint = "amount > 0"
                ),
                @CheckConstraint(
                        name = "chk_payments_status",
                        constraint = "status IN ('PENDING', 'COMPLETED', 'DECLINED', 'CANCELED')"
                ),
                @CheckConstraint(
                        name = "chk_payments_status_fields",
                        constraint = """
                                (
                                    status = 'PENDING'
                                    AND approved_transaction_key IS NULL
                                    AND failure_code IS NULL
                                    AND approved_at IS NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'COMPLETED'
                                    AND approved_transaction_key IS NOT NULL
                                    AND failure_code IS NULL
                                    AND approved_at IS NOT NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'DECLINED'
                                    AND approved_transaction_key IS NULL
                                    AND failure_code IS NOT NULL
                                    AND approved_at IS NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'CANCELED'
                                    AND approved_transaction_key IS NOT NULL
                                    AND failure_code IS NULL
                                    AND approved_at IS NOT NULL
                                    AND canceled_at IS NOT NULL
                                )
                                """
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "payment_key", nullable = false, updatable = false, length = 36)
    private String paymentKey;

    @Column(
            name = "request_hash",
            nullable = false,
            updatable = false,
            columnDefinition = "CHAR(64)"
    )
    private String requestHash;

    @Column(nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    @ColumnDefault("'PENDING'")
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(name = "approved_transaction_key", length = 36)
    private String approvedTransactionKey;

    @Column(name = "failure_code", length = 60)
    private String failureCode;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public Payment(String orderId, String paymentKey, String requestHash, Long amount) {

        validateText(orderId, 36, "주문번호");
        validateText(paymentKey, 36, "결제 키");
        validateHash(requestHash);

        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("결제 금액은 양수여야 합니다.");
        }

        this.orderId = orderId;
        this.paymentKey = paymentKey;
        this.requestHash = requestHash;
        this.amount = amount;
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
