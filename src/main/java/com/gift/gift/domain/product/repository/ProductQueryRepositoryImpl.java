package com.gift.gift.domain.product.repository;

import java.util.List;
import java.util.Objects;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final int MAX_FETCH_COUNT = 21;

    private final EntityManager entityManager;

    @Override
    public List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            int fetchCount
    ) {
        Objects.requireNonNull(condition, "상품 검색 조건은 필수입니다.");

        if (fetchCount < 1 || fetchCount > MAX_FETCH_COUNT) {
            throw new IllegalArgumentException(
                    "상품 조회 개수는 1 이상 21 이하여야 합니다."
            );
        }

        StringBuilder jpql = new StringBuilder("""
                SELECT new com.gift.gift.domain.product.repository.ProductSummaryProjection(
                    p.id,
                    p.name,
                    p.brand,
                    p.price
                )
                FROM Product p
                WHERE p.deletedAt IS NULL
                """);

        if (condition.hasQuery()) {
            jpql.append("""

                    AND (
                        p.name LIKE :keyword ESCAPE '!'
                        OR p.brand LIKE :keyword ESCAPE '!'
                    )
                    """);
        }

        if (condition.hasCategoryIds()) {
            jpql.append("""

                    AND p.category.id IN :categoryIds
                    """);
        }

        TypedQuery<ProductSummaryProjection> query =
                entityManager.createQuery(
                        jpql.toString(),
                        ProductSummaryProjection.class
                );

        if (condition.hasQuery()) {
            query.setParameter(
                    "keyword",
                    "%" + escapeLikeKeyword(condition.query()) + "%"
            );
        }

        if (condition.hasCategoryIds()) {
            query.setParameter("categoryIds", condition.categoryIds());
        }

        return query
                .setMaxResults(fetchCount)
                .getResultList();
    }

    private static String escapeLikeKeyword(String keyword) {
        return keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
