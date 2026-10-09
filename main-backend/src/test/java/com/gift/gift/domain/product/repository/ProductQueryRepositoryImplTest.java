package com.gift.gift.domain.product.repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.product.cursor.ProductCursor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ProductQueryRepositoryImplTest {

    @Test
    @DisplayName("Native SQL로 카테고리와 세 정렬의 커서를 적용하고 결과를 매핑한다")
    void searchUsesNativeSqlAndMapsProjection() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 4, 12, 0);
        for (ProductSort sort : List.of(
                ProductSort.POPULAR, ProductSort.MOST_GIFTED, ProductSort.NEWEST
        )) {
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.setMaxResults(21)).thenReturn(query);
            Object[] row = {7L, "수분크림", "브랜드", new BigDecimal("12000"),
                    10, 5, Timestamp.valueOf(createdAt), null};
            doReturn(List.<Object[]>of(row)).when(query).getResultList();
            ProductSearchCondition condition = new ProductSearchCondition(
                    "크림", List.of(11L, 14L), sort
            );
            ProductCursor cursor = new ProductCursor(
                    1, sort, "크림", condition.categoryIds(), 8L, createdAt,
                    sort == ProductSort.NEWEST ? null : 10,
                    sort == ProductSort.NEWEST ? null : 5
            );
            List<ProductSummaryProjection> result =
                    new ProductQueryRepositoryImpl(entityManager)
                            .searchProducts(condition, cursor, 21);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(entityManager).createNativeQuery(sql.capture());
            assertThat(sql.getValue())
                    .contains("MATCH(p.name, p.brand)", "IN BOOLEAN MODE",
                            "p.deleted_at IS NULL", "p.category_id IN (:categoryIds)",
                            "p.created_at < :cursor_createdAt")
                    .doesNotContain("p.createdAt", "SELECT new", " LIKE ");
            String order = switch (sort) {
                case POPULAR -> "p.views DESC, p.sales DESC, p.created_at DESC, p.id DESC";
                case MOST_GIFTED -> "p.sales DESC, p.views DESC, p.created_at DESC, p.id DESC";
                case NEWEST -> "p.created_at DESC, p.id DESC";
                default -> throw new AssertionError();
            };
            assertThat(sql.getValue()).contains("ORDER BY " + order);
            verify(query).setParameter("keyword", "\"크림\"");
            verify(query).setParameter("categoryIds", List.of(11L, 14L));
            verify(query).setParameter("cursor_createdAt", createdAt);
            verify(query).setParameter("cursor_id", 8L);
            assertThat(result).containsExactly(new ProductSummaryProjection(
                    7L, "수분크림", "브랜드", new BigDecimal("12000"), 10, 5, createdAt
            ));
        }
    }

    @Test
    @DisplayName("공백과 영문도 FULLTEXT 구문으로 전달하고 빈 검색어는 조건을 생략한다")
    void searchUsesFulltextPhraseForAllNonemptyInput() {
        for (String keyword : List.of("크", "수분 크림", "+크림", "100%_!", "cream", "크림\" -텀블러", "크림\\", "")) {
            EntityManager entityManager = mock(EntityManager.class);
            Query query = mock(Query.class);
            when(entityManager.createNativeQuery(anyString())).thenReturn(query);
            when(query.setMaxResults(21)).thenReturn(query);
            when(query.getResultList()).thenReturn(List.of());
            new ProductQueryRepositoryImpl(entityManager).searchProducts(
                    new ProductSearchCondition(keyword, List.of(), ProductSort.POPULAR),
                    null, 21
            );
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(entityManager).createNativeQuery(sql.capture());
            assertThat(sql.getValue()).doesNotContain("LIKE", "category_id IN");
            if (keyword.isEmpty()) {
                assertThat(sql.getValue()).doesNotContain("MATCH");
                verify(query, never()).setParameter(eq("keyword"), any());
            } else {
                assertThat(sql.getValue()).contains("MATCH(p.name, p.brand)", "IN BOOLEAN MODE");
                String expected = switch (keyword) {
                    case "크림\" -텀블러" -> "\"크림  -텀블러\"";
                    case "크림\\" -> "\"크림\"";
                    default -> "\"" + keyword + "\"";
                };
                verify(query).setParameter("keyword", expected);
            }
        }
    }
}
