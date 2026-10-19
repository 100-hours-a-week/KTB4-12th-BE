package com.gift.gift.domain.product.search;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductSearchOutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(Long productId) {
        int inserted = jdbcTemplate.update("""
                INSERT INTO product_search_outbox (
                    product_id,
                    product_version
                )
                SELECT id, search_version
                FROM products
                WHERE id = ?
                """, productId);

        if (inserted != 1) {
            throw new IllegalStateException(
                    "동기화할 상품이 존재하지 않습니다."
            );
        }
    }
}
