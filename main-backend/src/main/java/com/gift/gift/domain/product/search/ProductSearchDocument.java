package com.gift.gift.domain.product.search;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;

import com.gift.gift.domain.product.repository.ProductSummaryProjection;

@Document(
        indexName = "#{@environment.getProperty('app.product-search.index-name')}",
        createIndex = false
)
public record ProductSearchDocument(
        @Id
        Long id,

        String name,
        String brand,
        BigDecimal price,
        Integer views,
        Integer sales,

        @Field(name = "created_at")
        String createdAt
) {

    public ProductSummaryProjection toProjection() {
        // 초기 적재와 커서가 사용하는 한국 시간으로 복원한다.
        var localCreatedAt = OffsetDateTime.parse(createdAt)
                .withOffsetSameInstant(ZoneOffset.ofHours(9))
                .toLocalDateTime();

        return new ProductSummaryProjection(
                id,
                name,
                brand,
                price,
                views,
                sales,
                localCreatedAt
        );
    }
}
