package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

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

        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.name, p.brand, p.price,
                       p.views, p.sales, p.created_at
                FROM products p
                WHERE p.deleted_at IS NULL
                """);

        if (condition.hasQuery()) {
            sql.append("""

                    AND MATCH(p.name, p.brand)
                        AGAINST(:keyword IN BOOLEAN MODE)
                    """);
        }

        if (condition.hasCategoryIds()) {
            sql.append("""

                    AND p.category_id IN (:categoryIds)
                    """);
        }

        List<String> fields = sortFields(condition.sort());

        if (cursor != null) {
            sql.append(" AND ").append(cursorPredicate(fields));
        }

        StringJoiner orderBy = new StringJoiner(
                ", ",
                " ORDER BY ",
                ""
        );

        for (String field : fields) {
            orderBy.add("p." + columnName(field) + " DESC");
        }

        sql.append(orderBy);

        Query query = entityManager.createNativeQuery(sql.toString());

        if (condition.hasQuery()) {
            query.setParameter(
                    "keyword",
                    fulltextKeyword(condition.query())
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

        List<?> rows = query.setMaxResults(fetchCount).getResultList();
        return rows.stream()
                .map(row -> toProjection((Object[]) row))
                .toList();
    }

    private static ProductSummaryProjection toProjection(Object[] row) {
        LocalDateTime createdAt = row[6] instanceof Timestamp timestamp
                ? timestamp.toLocalDateTime()
                : (LocalDateTime) row[6];

        return new ProductSummaryProjection(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                (BigDecimal) row[3],
                ((Number) row[4]).intValue(),
                ((Number) row[5]).intValue(),
                createdAt
        );
    }

    private static String columnName(String field) {
        return "createdAt".equals(field) ? "created_at" : field;
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
            branch.add("p." + columnName(field) + " < :cursor_" + field);
            alternatives.add(branch.toString());

            // 다음 루프를 위해 현재 필드의 같음(=) 조건 누적
            equalities.add("p." + columnName(field) + " = :cursor_" + field);
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

    private static String fulltextKeyword(String keyword) {
        // 구문 밖으로 빠져나가지 않도록 구분용 따옴표와 역슬래시를 제거한다.
        String phrase = keyword.replace('"', ' ').replace('\\', ' ').strip();
        return "\"" + phrase + "\"";
    }
}
