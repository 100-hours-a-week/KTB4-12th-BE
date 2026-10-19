package com.gift.gift.domain.product.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.product.entity.ProductViewEvent;

public interface ProductViewEventRepository
        extends JpaRepository<ProductViewEvent, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO product_view_events (
                product_id, user_id, created_at, updated_at
            )
            VALUES (
                :productId, :userId, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
            )
            """, nativeQuery = true)
    int insertEvent(
            @Param("userId") Long userId,
            @Param("productId") Long productId
    );

    @Query(value = """
            SELECT id, product_id AS productId
            FROM product_view_events
            WHERE processed_at IS NULL
              AND deleted_at IS NULL
            ORDER BY id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<PendingProductViewEvent> findPendingForUpdate(
            @Param("batchSize") int batchSize
    );

    @Modifying
    @Query(value = """
            UPDATE product_view_events
            SET processed_at = CURRENT_TIMESTAMP(6),
                updated_at = CURRENT_TIMESTAMP(6)
            WHERE id IN (:eventIds)
              AND processed_at IS NULL
            """, nativeQuery = true)
    int markProcessed(@Param("eventIds") Collection<Long> eventIds);
}
