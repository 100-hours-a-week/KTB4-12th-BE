package com.gift.gift.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.product.entity.ProductView;

public interface ProductViewRepository extends JpaRepository<ProductView, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO product_views (
                user_id, product_id, last_viewed_at, created_at
            )
            VALUES (
                :userId, :productId,
                CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            )
            ON DUPLICATE KEY UPDATE
                last_viewed_at = IF(
                    last_viewed_at <= CURRENT_TIMESTAMP(6) - INTERVAL 24 HOUR,
                    CURRENT_TIMESTAMP(6),
                    last_viewed_at
                )
            """, nativeQuery = true)
    int recordViewAfterCooldown(
            @Param("userId") Long userId,
            @Param("productId") Long productId
    );
}
