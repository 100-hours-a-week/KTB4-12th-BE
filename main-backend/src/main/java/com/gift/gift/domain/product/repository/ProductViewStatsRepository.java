package com.gift.gift.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.product.entity.ProductViewStats;

public interface ProductViewStatsRepository extends JpaRepository<ProductViewStats, Long> {

    @Modifying
    @Query(value = """
            UPDATE product_view_stats
            SET views = views + :viewCount,
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE product_id = :productId
              AND deleted_at IS NULL
            """, nativeQuery = true)
    int incrementViewsIfActive(
            @Param("productId") Long productId,
            @Param("viewCount") int viewCount
    );
}
