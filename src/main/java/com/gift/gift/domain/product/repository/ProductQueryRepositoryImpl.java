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

        if (condition.sort() != ProductSort.AI_RECOMMENDED) {
            return searchGeneralProducts(
                    condition,
                    cursor,
                    fetchCount,
                    false
            );
        }

        // 인기 영역에 진입한 커서는 추천 영역으로 돌아가지 않는다.
        if (cursor != null
                && Boolean.FALSE.equals(cursor.recommendedRegion())) {
            return searchGeneralProducts(
                    condition,
                    cursor,
                    fetchCount,
                    true
            );
        }

        List<ProductSummaryProjection> recommendations =
                searchRecommendedProducts(
                        condition,
                        cursor,
                        fetchCount
                );

        int remainingCount = fetchCount - recommendations.size();

        if (remainingCount == 0) {
            return recommendations;
        }

        // 추천 영역 뒤에 붙는 인기 영역은 첫 상품부터 조회한다.
        List<ProductSummaryProjection> popularProducts =
                searchGeneralProducts(
                        condition,
                        null,
                        remainingCount,
                        true
                );

        List<ProductSummaryProjection> result =
                new ArrayList<>(fetchCount);
        result.addAll(recommendations);
        result.addAll(popularProducts);

        return List.copyOf(result);
    }

    @Override
    public boolean hasMatchingRecommendedProducts(
            ProductSearchCondition condition
    ) {
        Objects.requireNonNull(condition, "상품 검색 조건은 필수입니다.");

        if (!condition.isAiRequested()) {
            throw new IllegalArgumentException(
                    "AI 추천 요청 조건이 필요합니다."
            );
        }

        StringBuilder sql = new StringBuilder("""
            SELECT 1
            FROM recipient_recommended_products r
            JOIN products p ON p.id = r.product_id
            WHERE r.recipient_id = :recipientUserId
              AND r.source_version = :analyzedSourceVersion
            """);

        appendProductFilters(sql, condition);

        Query query = entityManager.createNativeQuery(sql.toString());
        bindProductFilters(query, condition);
        bindRecommendationContext(query, condition);

        return !query.setMaxResults(1).getResultList().isEmpty();
    }

    private List<ProductSummaryProjection> searchRecommendedProducts(
            ProductSearchCondition condition,
            ProductCursor cursor,
            int fetchCount
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.name, p.brand, p.price,
                   p.views, p.sales, p.created_at, r.rank_order
            FROM recipient_recommended_products r
            JOIN products p ON p.id = r.product_id
            WHERE r.recipient_id = :recipientUserId
              AND r.source_version = :analyzedSourceVersion
            """);

        appendProductFilters(sql, condition);

        if (cursor != null) {
            sql.append("""

                AND (
                    r.rank_order > :cursorRankOrder
                    OR (
                        r.rank_order = :cursorRankOrder
                        AND p.id > :cursorProductId
                    )
                )
                """);
        }

        sql.append(" ORDER BY r.rank_order ASC, p.id ASC");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindProductFilters(query, condition);
        bindRecommendationContext(query, condition);

        if (cursor != null) {
            query.setParameter("cursorRankOrder", cursor.rankOrder());
            query.setParameter("cursorProductId", cursor.productId());
        }

        return readProducts(query, fetchCount);
    }

    private List<ProductSummaryProjection> searchGeneralProducts(
            ProductSearchCondition condition,
            ProductCursor cursor,
            int fetchCount,
            boolean excludeRecommendations
    ) {
        StringBuilder sql = new StringBuilder("""
            SELECT p.id, p.name, p.brand, p.price,
                   p.views, p.sales, p.created_at, NULL
            FROM products p
            WHERE 1 = 1
            """);

        appendProductFilters(sql, condition);

        if (excludeRecommendations) {
            sql.append("""
                AND NOT EXISTS (
                    SELECT 1
                    FROM recipient_recommended_products r
                    WHERE r.product_id = p.id
                      AND r.recipient_id = :recipientUserId
                      AND r.source_version = :analyzedSourceVersion
                )
                """);
        }

        // AI 추천 뒤의 일반 영역은 인기순이다.
        ProductSort generalSort = condition.sort() == ProductSort.AI_RECOMMENDED
                        ? ProductSort.POPULAR
                        : condition.sort();

        List<String> fields = sortFields(generalSort);

        if (cursor != null) {
            sql.append(" AND ").append(cursorPredicate(fields));
        }

        StringJoiner orderBy = new StringJoiner(", ", " ORDER BY ", "");

        for (String field : fields) {
            orderBy.add("p." + columnName(field) + " DESC");
        }

        sql.append(orderBy);

        Query query = entityManager.createNativeQuery(sql.toString());
        bindProductFilters(query, condition);

        if (excludeRecommendations) {
            bindRecommendationContext(query, condition);
        }

        if (cursor != null) {
            for (String field : fields) {
                query.setParameter(
                        "cursor_" + field,
                        cursorValue(cursor, field)
                );
            }
        }

        return readProducts(query, fetchCount);
    }

    private void appendProductFilters(
            StringBuilder sql,
            ProductSearchCondition condition
    ) {
        sql.append(" AND p.deleted_at IS NULL");

        // AI 조회와 그 Fallback에서 품절 상품을 제외한다.
        if (condition.isAiRequested()) {
            sql.append(" AND p.quantity > 0");
        }

        if (condition.hasQuery()) {
            sql.append("""

                AND MATCH(p.name, p.brand)
                    AGAINST(:keyword IN BOOLEAN MODE)
                """);
        }

        if (condition.hasCategoryIds()) {
            sql.append(" AND p.category_id IN (:categoryIds)");
        }
    }

    private void bindProductFilters(
            Query query,
            ProductSearchCondition condition
    ) {
        if (condition.hasQuery()) {
            query.setParameter("keyword", fulltextKeyword(condition.query()));
        }

        if (condition.hasCategoryIds()) {
            query.setParameter("categoryIds", condition.categoryIds());
        }
    }

    private void bindRecommendationContext(
            Query query,
            ProductSearchCondition condition
    ) {
        query.setParameter(
                "recipientUserId",
                condition.recipientUserId()
        );
        query.setParameter(
                "analyzedSourceVersion",
                condition.analyzedSourceVersion()
        );
    }

    private List<ProductSummaryProjection> readProducts(
            Query query,
            int fetchCount
    ) {
        List<?> rows = query.setMaxResults(fetchCount).getResultList();

        return rows.stream()
                .map(row -> toProjection((Object[]) row))
                .toList();
    }

    private static ProductSummaryProjection toProjection(Object[] row) {
        LocalDateTime createdAt = row[6] instanceof Timestamp timestamp
                ? timestamp.toLocalDateTime()
                : (LocalDateTime) row[6];

        Integer rankOrder = row[7] == null
                ? null
                : ((Number) row[7]).intValue();

        return new ProductSummaryProjection(
                ((Number) row[0]).longValue(),
                (String) row[1],
                (String) row[2],
                (BigDecimal) row[3],
                ((Number) row[4]).intValue(),
                ((Number) row[5]).intValue(),
                createdAt,
                rankOrder
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
