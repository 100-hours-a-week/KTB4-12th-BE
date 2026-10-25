package com.gift.gift.domain.coupon.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "coupon_inventories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_coupon_inventories_user_multiplier",
                columnNames = {"user_id", "multiplier"}
        ),
        check = {
                @CheckConstraint(
                        name = "chk_coupon_inventories_multiplier",
                        constraint = "multiplier IN (2, 3)"
                ),
                @CheckConstraint(
                        name = "chk_coupon_inventories_quantity",
                        constraint = "quantity >= 0"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponInventory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false, updatable = false)
    private Integer multiplier;

    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer quantity;

    public CouponInventory(Long userId, int multiplier) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("사용자 ID는 양수여야 합니다.");
        }

        if (multiplier != 2 && multiplier != 3) {
            throw new IllegalArgumentException("쿠폰 배수는 2 또는 3이어야 합니다.");
        }

        this.userId = userId;
        this.multiplier = multiplier;
        this.quantity = 0;
    }
}
