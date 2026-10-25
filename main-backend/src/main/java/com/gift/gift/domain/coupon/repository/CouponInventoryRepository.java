package com.gift.gift.domain.coupon.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.coupon.dto.response.CouponResponse;
import com.gift.gift.domain.coupon.entity.CouponInventory;

public interface CouponInventoryRepository
        extends JpaRepository<CouponInventory, Long> {

    @Modifying(flushAutomatically = true)
    @Query("""
            update CouponInventory inventory
            set inventory.quantity = inventory.quantity + 1,
                inventory.updatedAt = :updatedAt
            where inventory.userId = :userId
              and inventory.multiplier = :multiplier
              and inventory.quantity <
            """ + Integer.MAX_VALUE)
    int increaseQuantity(
            @Param("userId") Long userId,
            @Param("multiplier") int multiplier,
            @Param("updatedAt") LocalDateTime updatedAt
    );

    @Query("""
            select new com.gift.gift.domain.coupon.dto.response.CouponResponse(
                inventory.multiplier,
                inventory.quantity
            )
            from CouponInventory inventory
            where inventory.userId = :userId
              and inventory.quantity > 0
            order by inventory.multiplier asc
            """)
    List<CouponResponse> findAvailableCoupons(
            @Param("userId") Long userId
    );
}
