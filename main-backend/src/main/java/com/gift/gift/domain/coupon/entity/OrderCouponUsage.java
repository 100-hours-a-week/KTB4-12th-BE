package com.gift.gift.domain.coupon.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "order_coupon_usages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_order_coupon_usages_order",
                columnNames = "order_record_id"
        ),
        check = @CheckConstraint(
                name = "chk_order_coupon_usages_status",
                constraint = "status IN ('RESERVED', 'USED', 'RESTORED')"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCouponUsage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "order_record_id",
            nullable = false,
            updatable = false
    )
    private Long orderRecordId;

    @Column(
            name = "coupon_inventory_id",
            nullable = false,
            updatable = false
    )
    private Long couponInventoryId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private OrderCouponUsageStatus status =
            OrderCouponUsageStatus.RESERVED;

    public OrderCouponUsage(
            Long orderRecordId,
            Long couponInventoryId
    ) {
        if (orderRecordId == null || orderRecordId <= 0) {
            throw new IllegalArgumentException(
                    "주문 레코드 ID는 양수여야 합니다."
            );
        }

        if (couponInventoryId == null || couponInventoryId <= 0) {
            throw new IllegalArgumentException(
                    "쿠폰 보유 정보 ID는 양수여야 합니다."
            );
        }

        this.orderRecordId = orderRecordId;
        this.couponInventoryId = couponInventoryId;
    }
}
