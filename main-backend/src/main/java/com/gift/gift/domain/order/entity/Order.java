package com.gift.gift.domain.order.entity;

import java.time.LocalDateTime;

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
        name = "orders",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_orders_order_id", columnNames = "order_id"),
                @UniqueConstraint(name = "uk_orders_payment_key", columnNames = "payment_key"),
                @UniqueConstraint(
                        name = "uk_orders_payer_idempotency",
                        columnNames = {"payer_id", "idempotency_key"}
                ),
                @UniqueConstraint(
                        name = "uk_orders_approved_transaction_key",
                        columnNames = "approved_transaction_key"
                )
        },
        indexes = {
                @Index(name = "idx_orders_payer_created", columnList = "payer_id, created_at, id"),
                @Index(name = "idx_orders_status_updated", columnList = "status, updated_at")
        },
        check = {
                @CheckConstraint(name = "chk_orders_quantity", constraint = "quantity BETWEEN 1 AND 10"),
                @CheckConstraint(
                        name = "chk_orders_unit_price_non_negative",
                        constraint = "unit_price >= 0"
                ),
                @CheckConstraint(name = "chk_orders_amount_non_negative", constraint = "amount >= 0"),
                @CheckConstraint(
                        name = "chk_orders_status",
                        constraint = "status IN ('PENDING', 'COMPLETED', 'DECLINED', 'FAILED', 'CANCELED')"
                ),
                @CheckConstraint(
                        name = "chk_orders_approval_result",
                        constraint = "approval_result IS NULL OR approval_result IN ('APPROVED', 'DECLINED', 'UNKNOWN')"
                ),
                @CheckConstraint(
                        name = "chk_orders_failure_code",
                        constraint = """
                                failure_code IS NULL
                                OR failure_code IN (
                                    'PAYMENT_DECLINED',
                                    'PAYMENT_RESULT_UNCONFIRMED',
                                    'PAYMENT_SERVER_UNAVAILABLE',
                                    'LOCAL_PROCESSING_FAILED'
                                )
                                """
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false, length = 36)
    private String orderId;

    @Column(name = "payment_key", length = 36)
    private String paymentKey;

    @Column(name = "payer_id", nullable = false, updatable = false)
    private Long payerId;

    @Column(name = "recipient_id", nullable = false, updatable = false)
    private Long recipientId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private Long productId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private Integer quantity;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private Long unitPrice;

    @Column(name = "amount", nullable = false, updatable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "status", nullable = false, length = 20)
    private OrderStatus status = OrderStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "approval_result", length = 20)
    private PaymentApprovalResult approvalResult;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 100)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false, columnDefinition = "CHAR(64)")
    private String requestHash;

    @Column(name = "approved_transaction_key", length = 36)
    private String approvedTransactionKey;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "failure_code", length = 60)
    private OrderFailureCode failureCode;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    public Order(
            String orderId,
            Long payerId,
            Long recipientId,
            Long productId,
            Integer quantity,
            Long unitPrice,
            Long amount,
            String idempotencyKey,
            String requestHash
    ) {
        validateText(orderId, 36, "주문번호");
        validateId(payerId, "결제자 ID");
        validateId(recipientId, "수신자 ID");
        validateId(productId, "상품 ID");
        if (quantity == null || quantity < 1 || quantity > 10) {
            throw new IllegalArgumentException("주문 수량은 1개 이상 10개 이하여야 합니다.");
        }
        if (unitPrice == null || unitPrice < 0) {
            throw new IllegalArgumentException("상품 단가는 0 이상이어야 합니다.");
        }
        if (amount == null || amount < 0) {
            throw new IllegalArgumentException("결제 금액은 0 이상이어야 합니다.");
        }
        validateText(idempotencyKey, 100, "멱등성 키");
        validateHash(requestHash);

        this.orderId = orderId;
        this.payerId = payerId;
        this.recipientId = recipientId;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
        this.amount = amount;
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
