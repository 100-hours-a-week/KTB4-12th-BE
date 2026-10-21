package com.gift.gift.domain.product.search;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOptions;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Repository;

import com.gift.gift.domain.product.cursor.ProductCursor;
import com.gift.gift.domain.product.cursor.ProductCursorValidator;
import com.gift.gift.domain.product.repository.ProductSearchCondition;

@Repository
@ConditionalOnProperty(
        prefix = "app.product-search",
        name="enabled",
        havingValue = "true"
)
public class ProductSearchRepository {

    // 초기 적재에서 MySQL DATETIME을 해석한 시간대와 일치해야 한다.
    private static final ZoneOffset SOURCE_OFFSET = ZoneOffset.ofHours(9);

    private static final int MAX_FETCH_COUNT = 21;

    private final ElasticsearchOperations operations;
    private final IndexCoordinates index;

    public ProductSearchRepository(
            ElasticsearchOperations operations,
            @Value("${app.product-search.index-name}") String indexName
    ) {
        this.operations = operations;
        this.index = IndexCoordinates.of(indexName);
    }

    public SearchHits<ProductSearchDocument> searchFirstPage(
            ProductSearchCondition condition
    ) {
        return search(condition, null, MAX_FETCH_COUNT);
    }

    public SearchHits<ProductSearchDocument> search(
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

        if (!condition.hasQuery()
                || condition.query().codePointCount(
                0,
                condition.query().length()
        ) < 2) {
            throw new IllegalArgumentException(
                    "이 검색 경로는 두 글자 이상 검색어를 대상으로 합니다."
            );
        }

        List<String> sortFields = switch (condition.sort()) {
            case POPULAR ->
                    List.of("views", "sales", "created_at", "id");
            case MOST_GIFTED ->
                    List.of("sales", "views", "created_at", "id");
            case NEWEST ->
                    List.of("created_at", "id");
            case AI_RECOMMENDED ->
                    throw new IllegalArgumentException(
                            "AI 추천 조회는 기존 추천 검색 경로를 사용합니다."
                    );
        };

        List<Query> filters = new ArrayList<>();

        filters.add(Query.of(query -> query.bool(bool -> bool
                .should(clause -> clause.matchPhrase(phrase -> phrase
                        .field("name")
                        .query(condition.query())
                        .slop(0)))
                .should(clause -> clause.matchPhrase(phrase -> phrase
                        .field("brand")
                        .query(condition.query())
                        .slop(0)))
                .minimumShouldMatch("1")
        )));

        if (condition.hasCategoryIds()) {
            List<FieldValue> categoryIds = condition.categoryIds()
                    .stream()
                    .map(id -> FieldValue.of(id.toString()))
                    .toList();

            filters.add(Query.of(query -> query.terms(terms -> terms
                    .field("category_id")
                    .terms(values -> values.value(categoryIds))
            )));
        }

        List<SortOptions> sorts = sortFields.stream()
                .map(field -> SortOptions.of(sort -> sort.field(value -> {
                    value.field(field).order(SortOrder.Desc);

                    if ("created_at".equals(field)) {
                        value.format("strict_date_optional_time_nanos");
                    }

                    return value;
                })))
                .toList();

        NativeQuery query = NativeQuery.builder()
                .withQuery(value -> value.bool(bool -> bool
                        .filter(filters)
                        .mustNot(clause -> clause.exists(exists -> exists
                                .field("deleted_at")))))
                .withSort(sorts)
                .withPageable(PageRequest.of(0, fetchCount))
                .build();

        query.setTrackTotalHits(false);
        query.setTrackScores(false);
        query.setRequestCache(false);

        if (cursor != null) {
            query.setSearchAfter(searchAfter(cursor, sortFields));
        }

        return operations.search(
                query,
                ProductSearchDocument.class,
                index
        );
    }

    private List<Object> searchAfter(
            ProductCursor cursor,
            List<String> sortFields
    ) {
        List<Object> values = new ArrayList<>(sortFields.size());

        for (String field : sortFields) {
            Object value = switch (field) {
                case "views" -> cursor.views();
                case "sales" -> cursor.sales();
                case "created_at" -> cursor.createdAt()
                        .atOffset(SOURCE_OFFSET)
                        .toInstant()
                        .toString();
                case "id" -> cursor.productId();
                default -> throw new IllegalArgumentException(
                        "지원하지 않는 정렬 필드입니다: " + field
                );
            };

            values.add(value);
        }

        return values;
    }
}
