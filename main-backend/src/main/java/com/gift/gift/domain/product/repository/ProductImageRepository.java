package com.gift.gift.domain.product.repository;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.product.entity.ProductImage;

public interface ProductImageRepository
        extends JpaRepository<ProductImage, Long> {

    default List<ProductImageProjection> findThumbnailCandidates(
            Collection<Long> productIds
    ) {
        Objects.requireNonNull(productIds, "상품 ID 목록은 필수입니다.");

        if (productIds.isEmpty()) {
            return List.of();
        }

        return findActiveImagesForThumbnails(productIds);
    }

    @Query("""
            SELECT new com.gift.gift.domain.product.repository.ProductImageProjection(
                i.product.id,
                i.objectKey
            )
            FROM ProductImage i
            WHERE i.product.id IN :productIds
              AND i.deletedAt IS NULL
            ORDER BY
                i.product.id ASC,
                CASE WHEN i.sortOrder IS NULL THEN 1 ELSE 0 END ASC,
                i.sortOrder ASC,
                i.createdAt ASC,
                i.id ASC
            """)
    List<ProductImageProjection> findActiveImagesForThumbnails(
            @Param("productIds") Collection<Long> productIds
    );

    @Query("""
        SELECT i
        FROM ProductImage i
        WHERE i.product.id = :productId
          AND i.deletedAt IS NULL
        ORDER BY
            CASE WHEN i.sortOrder IS NULL THEN 1 ELSE 0 END ASC,
            i.sortOrder ASC,
            i.createdAt ASC,
            i.id ASC
        """)
    List<ProductImage> findActiveDetailImages(
            @Param("productId") Long productId
    );
}
