package com.gift.gift.domain.gift.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
//@Table(name = "gift_histories", uniqueConstraints = {
//        @UniqueConstraint(
//                name = "uk_gift_histories_sender_idempotency_key",
//                columnNames = {"sender_id", "idempotency_key"}
//        )
//})
@Table(name = "gift_histories")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GiftHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // TODO: User 엔티티 구현 후 import를 추가하고 연관관계 매핑을 활성화한다.
    // @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // @JoinColumn(name = "sender_id", nullable = false)
    // private User sender;

    // TODO: User 엔티티 구현 후 import를 추가하고 연관관계 매핑을 활성화한다.
    // @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // @JoinColumn(name = "recipient_id", nullable = false)
    // private User recipient;

    // TODO: Product 엔티티 구현 후 import를 추가하고 연관관계 매핑을 활성화한다.
    // @ManyToOne(fetch = FetchType.LAZY, optional = false)
    // @JoinColumn(name = "product_id", nullable = false)
    // private Product product;

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
    private GiftStatus status = GiftStatus.PROCESSING;

    @Column(name = "idempotency_key", columnDefinition = "BINARY(16)")
    private UUID idempotencyKey;

    @Column(name = "request_fingerprint", columnDefinition = "CHAR(64)")
    private String requestFingerprint;

    private LocalDateTime completedAt;

    @CreatedDate
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    //    TODO: User, Product 엔티티 구현 후 import를 추가하고 연관관계 매핑을 활성화한다.
    //    public GiftHistory(User sender, User recipient, Product product, Integer quantity, BigDecimal productPriceSnapshot, String productNameSnapshot, UUID idempotencyKey, String requestFingerprint){}

    public GiftHistory(Integer quantity, BigDecimal productPriceSnapshot, String productNameSnapshot,
                       UUID idempotencyKey, String requestFingerprint) {
        this.quantity = quantity;
        this.productPriceSnapshot = productPriceSnapshot;
        this.productNameSnapshot = productNameSnapshot;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
    }
}
