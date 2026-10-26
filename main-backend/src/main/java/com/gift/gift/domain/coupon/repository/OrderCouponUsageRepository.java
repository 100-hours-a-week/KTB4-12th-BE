package com.gift.gift.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.coupon.entity.OrderCouponUsage;
import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;

public interface OrderCouponUsageRepository
        extends JpaRepository<OrderCouponUsage, Long> {

    Optional<OrderCouponUsage> findByOrderRecordId(Long orderRecordId);

    @Query("""
            select inventory.multiplier as multiplier, usage.status as status
            from OrderCouponUsage usage
            join CouponInventory inventory on inventory.id = usage.couponInventoryId
            where usage.orderRecordId = :orderRecordId
            """)
    Optional<CouponSelection> findSelectionByOrderRecordId(@Param("orderRecordId") Long orderRecordId);

    interface CouponSelection {
        int getMultiplier();
        OrderCouponUsageStatus getStatus();
    }

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderCouponUsage usage
            set usage.status = :restoredStatus,
                usage.updatedAt = :updatedAt
            where usage.id = :usageId
              and usage.status = :reservedStatus
            """)
    int restoreReserved(
            @Param("usageId") Long usageId,
            @Param("reservedStatus") OrderCouponUsageStatus reservedStatus,
            @Param("restoredStatus") OrderCouponUsageStatus restoredStatus,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderCouponUsage usage
            set usage.status = com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus.USED,
                usage.updatedAt = :updatedAt
            where usage.id = :usageId
              and usage.status = com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus.RESERVED
            """)
    int confirmUsed(@Param("usageId") Long usageId, @Param("updatedAt") LocalDateTime updatedAt);

    @Modifying(flushAutomatically = true)
    @Query("""
            update OrderCouponUsage usage
            set usage.status = com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus.RESTORED,
                usage.updatedAt = :updatedAt
            where usage.id = :usageId
              and usage.status = com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus.USED
            """)
    int restoreUsed(@Param("usageId") Long usageId, @Param("updatedAt") LocalDateTime updatedAt);
}
