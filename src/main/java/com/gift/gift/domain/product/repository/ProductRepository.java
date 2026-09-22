package com.gift.gift.domain.product.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.product.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long>, ProductQueryRepository {
    Optional<Product> findByIdAndDeletedAtIsNull(Long productId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT product
            FROM Product product
            WHERE product.id = :productId
              AND product.deletedAt IS NULL
            """)
    Optional<Product> findActiveByIdForUpdate(
            @Param("productId") Long productId
    );

    @Modifying
    @Query(value = """
        UPDATE products
        SET views = views + 1,
            updated_at = CURRENT_TIMESTAMP(6)
        WHERE id = :productId
          AND deleted_at IS NULL
        """, nativeQuery = true)
    int incrementViewsIfActive(@Param("productId") Long productId);
}
