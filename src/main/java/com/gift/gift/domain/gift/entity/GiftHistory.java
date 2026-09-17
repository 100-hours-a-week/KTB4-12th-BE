package com.gift.gift.domain.gift.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(name = "gift_histories", uniqueConstraints = {
        @UniqueConstraint(
                name = "uk_gift_histories_sender_idempotency_key",
                columnNames = {"sender_id", "idempotency_key"}
        )
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, check = @CheckConstraint(
            name = "chk_gift_quantity_positive",
            constraint = "quantity >= 1"
    ))
    private Integer quantity;

    @Column(precision = 12, scale = 0, nullable = false)
    private BigDecimal productPriceSnapshot;

    @Column(nullable = false, length = 255)
    private String productNameSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GiftStatus status = GiftStatus.COMPLETED;

    @Column(name = "idempotency_key", nullable = false, columnDefinition = "BINARY(16)")
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    @Column(name = "completed_at", nullable = false)
    private LocalDateTime completedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public GiftHistory(User sender, User recipient, Product product, Integer quantity, BigDecimal productPriceSnapshot,
                       String productNameSnapshot, UUID idempotencyKey, String requestFingerprint) {
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
}
