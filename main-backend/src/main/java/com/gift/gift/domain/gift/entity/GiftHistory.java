package com.gift.gift.domain.gift.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(name = "gift_histories", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_gift_histories_sender_idempotency",
                columnNames = {"sender_id", "idempotency_key"}
        ),
        @UniqueConstraint(
                name = "uk_gift_histories_order_record",
                columnNames = "order_record_id"
        )
}, indexes = {
        @Index(
                name = "idx_gift_histories_sender_completed",
                columnList = "sender_id, completed_at DESC, id DESC"
        ),
        @Index(
                name = "idx_gift_histories_recipient_completed",
                columnList = "recipient_id, completed_at DESC, id DESC"
        ),
        @Index(
                name = "idx_gifts_recipient_status",
                columnList = "recipient_id, status, created_at, id"
        ),
        @Index(
                name = "idx_gifts_sender_status",
                columnList = "sender_id, status, created_at, id"
        ),
        @Index(
                name = "idx_gifts_expiration",
                columnList = "status, expires_at, id"
        )
}, check = {
        @CheckConstraint(
                name = "chk_gift_histories_distinct_users",
                constraint = "sender_id <> recipient_id"
        ),
        @CheckConstraint(
                name = "chk_gift_histories_quantity_positive",
                constraint = "quantity >= 1"
        ),
        @CheckConstraint(
                name = "chk_gift_histories_price_non_negative",
                constraint = "product_price_snapshot >= 0"
        ),
        @CheckConstraint(
                name = "chk_gift_histories_status",
                constraint = "status IN ('PROCESSING', 'WAITING_FOR_ACCEPTANCE', 'COMPLETED', 'FAILED', 'CANCELED')"
        ),
        @CheckConstraint(
                name = "chk_gift_histories_status_times",
                constraint = """
                                status IN ('PROCESSING', 'FAILED')
                                OR (
                                    status = 'WAITING_FOR_ACCEPTANCE'
                                    AND expires_at IS NOT NULL
                                    AND completed_at IS NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'COMPLETED'
                                    AND completed_at IS NOT NULL
                                    AND canceled_at IS NULL
                                )
                                OR (
                                    status = 'CANCELED'
                                    AND completed_at IS NULL
                                    AND canceled_at IS NOT NULL
                                )
                                """
        )
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_record_id", updatable = false)
    private Long orderRecordId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "sender_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gift_histories_sender")
    )
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "recipient_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gift_histories_recipient")
    )
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_gift_histories_product")
    )
    private Product product;

    @Column(nullable = false)
    private Integer quantity;

    @Column(name = "product_price_snapshot", precision = 12, scale = 0, nullable = false)
    private BigDecimal productPriceSnapshot;

    @Column(name = "product_name_snapshot", nullable = false, length = 255)
    private String productNameSnapshot;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 32)
    @ColumnDefault("'COMPLETED'")
    private GiftStatus status = GiftStatus.COMPLETED;

    @Column(name = "idempotency_key", nullable = false, columnDefinition = "BINARY(16)")
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public GiftHistory(
            User sender,
            User recipient,
            Product product,
            Integer quantity,
            BigDecimal productPriceSnapshot,
            String productNameSnapshot,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        validateCommon(
                sender,
                recipient,
                product,
                quantity,
                productPriceSnapshot,
                productNameSnapshot,
                idempotencyKey,
                requestFingerprint
        );

        this.sender = sender;
        this.recipient = recipient;
        this.product = product;
        this.quantity = quantity;
        this.productPriceSnapshot = productPriceSnapshot;
        this.productNameSnapshot = productNameSnapshot;
        this.status = GiftStatus.COMPLETED;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.completedAt = LocalDateTime.now();
    }

    private GiftHistory(
            Long orderRecordId,
            User sender,
            User recipient,
            Product product,
            Integer quantity,
            BigDecimal productPriceSnapshot,
            String productNameSnapshot,
            UUID idempotencyKey,
            String requestFingerprint,
            LocalDateTime expiresAt
    ) {
        validateCommon(
                sender,
                recipient,
                product,
                quantity,
                productPriceSnapshot,
                productNameSnapshot,
                idempotencyKey,
                requestFingerprint
        );

        if (orderRecordId == null || orderRecordId <= 0) {
            throw new IllegalArgumentException("주문 레코드 ID는 양수여야 합니다.");
        }

        if (expiresAt == null) {
            throw new IllegalArgumentException(
                    "선물 만료 시각은 필수입니다."
            );
        }

        this.orderRecordId = orderRecordId;
        this.sender = sender;
        this.recipient = recipient;
        this.product = product;
        this.quantity = quantity;
        this.productPriceSnapshot = productPriceSnapshot;
        this.productNameSnapshot = productNameSnapshot;
        this.status = GiftStatus.WAITING_FOR_ACCEPTANCE;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt;
    }

    public static GiftHistory waitingForAcceptance(
            Long orderRecordId,
            User sender,
            User recipient,
            Product product,
            Integer quantity,
            BigDecimal productPriceSnapshot,
            String productNameSnapshot,
            UUID idempotencyKey,
            String requestFingerprint,
            LocalDateTime expiresAt
    ) {
        return new GiftHistory(
                orderRecordId,
                sender,
                recipient,
                product,
                quantity,
                productPriceSnapshot,
                productNameSnapshot,
                idempotencyKey,
                requestFingerprint,
                expiresAt
        );
    }

    private static void validateCommon(
            User sender,
            User recipient,
            Product product,
            Integer quantity,
            BigDecimal productPriceSnapshot,
            String productNameSnapshot,
            UUID idempotencyKey,
            String requestFingerprint
    ) {
        if (sender == null || recipient == null || product == null) {
            throw new IllegalArgumentException("보낸 사람, 받는 사람, 상품은 필수입니다.");
        }

        if (quantity == null
                || productPriceSnapshot == null
                || productNameSnapshot == null
                || idempotencyKey == null
                || requestFingerprint == null) {
            throw new IllegalArgumentException("수량, 가격 스냅샷, 상품명 스냅샷, 멱등성 키, 요청 지문은 필수입니다.");
        }
    }
}
