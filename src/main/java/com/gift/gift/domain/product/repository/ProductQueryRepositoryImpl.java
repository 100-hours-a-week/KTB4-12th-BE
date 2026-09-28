package com.gift.gift.domain.product.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import lombok.RequiredArgsConstructor;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorValidator;

@RequiredArgsConstructor
public class ProductQueryRepositoryImpl implements ProductQueryRepository {

    private static final int MAX_FETCH_COUNT = 21;

    private final EntityManager entityManager;

    @Override
    public List<ProductSummaryProjection> searchProducts(
            ProductSearchCondition condition,
            ProductCursor cursor,
            int fetchCount
    ) {
        Objects.requireNonNull(condition, "상품 검색 조건은 필수입니다.");

        if (fetchCount < 1 || fetchCount > MAX_FETCH_COUNT) {
            throw new IllegalArgumentException(
                    "상품 조회 개수는 1 이상 21 이하여야 합니다."
            );
        }

        if (cursor != null) {
            ProductCursorValidator.validate(cursor, condition);
        }

        StringBuilder jpql = new StringBuilder("""
                SELECT new com.gift.gift.domain.product.repository.ProductSummaryProjection(
                    p.id,
                    p.name,
                    p.brand,
                    p.price,
                    p.views,
                    p.sales,
                    p.createdAt
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

        List<String> fields = sortFields(condition.sort());

        if (cursor != null) {
            jpql.append(" AND ").append(cursorPredicate(fields));
        }

        StringJoiner orderBy = new StringJoiner(
                ", ",
                " ORDER BY ",
                ""
        );

        for (String field : fields) {
            orderBy.add("p." + field + " DESC");
        }

        jpql.append(orderBy);

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

        if (cursor != null) {
            for (String field : fields) {
                query.setParameter(
                        "cursor_" + field,
                        cursorValue(cursor, field)
                );
            }
        }

        return query
                .setMaxResults(fetchCount)
                .getResultList();
    }

    private static List<String> sortFields(ProductSort sort) {
        return switch (sort) {
            case AI_RECOMMENDED ->
                    throw new IllegalArgumentException(
                            "AI 추천 정렬은 일반 상품 조회에 사용할 수 없습니다."
                    );
            case POPULAR ->
                    List.of("views", "sales", "createdAt", "id");
            case MOST_GIFTED ->
                    List.of("sales", "views", "createdAt", "id");
            case NEWEST ->
                    List.of("createdAt", "id");
        };
    }

    private static String cursorPredicate(List<String> fields) {
        StringJoiner alternatives =
                new StringJoiner(" OR ", "(", ")");

        // 이전 필드들이 모두 같은지(=) 조건 추가
        List<String> equalities = new ArrayList<>();

        for (String field : fields) {
            StringJoiner branch =
                    new StringJoiner(" AND ", "(", ")");

            for (String equality : equalities) {
                branch.add(equality);
            }

            // 현재 필드가 더 작은지(<) 조건 추가
            branch.add("p." + field + " < :cursor_" + field);
            alternatives.add(branch.toString());

            // 다음 루프를 위해 현재 필드의 같음(=) 조건 누적
            equalities.add("p." + field + " = :cursor_" + field);
        }

        return alternatives.toString();
    }

    private static Object cursorValue(
            ProductCursor cursor,
            String field
    ) {
        return switch (field) {
            case "views" -> cursor.views();
            case "sales" -> cursor.sales();
            case "createdAt" -> cursor.createdAt();
            case "id" -> cursor.productId();
            default -> throw new IllegalArgumentException(
                    "지원하지 않는 정렬 필드입니다."
            );
        };
    }

    private static String escapeLikeKeyword(String keyword) {
        return keyword
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
    }
}
