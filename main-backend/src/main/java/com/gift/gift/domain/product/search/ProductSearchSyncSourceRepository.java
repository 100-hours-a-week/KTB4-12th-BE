package com.gift.gift.domain.product.search;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ProductSearchSyncSourceRepository {

    private final JdbcTemplate jdbcTemplate;

    public Map<Long, Snapshot> findLatest(List<Long> productIds) {
        if (productIds.isEmpty()) {
            return Map.of();
        }

        List<Snapshot> snapshots =
                new NamedParameterJdbcTemplate(jdbcTemplate).query("""
                                SELECT p.id, p.category_id, p.name, p.brand,
                                       p.description, p.price, p.quantity,
                                       s.views, p.sales, p.created_at, p.updated_at,
                                       p.deleted_at, p.search_version
                                FROM products p
                                LEFT JOIN product_view_stats s ON s.product_id = p.id
                                WHERE p.id IN (:ids)
                                """,
                        new MapSqlParameterSource("ids", productIds),
                        (rs, row) -> {
                            long id = rs.getLong("id");
                            long version = rs.getLong("search_version");

                            Map<String, Object> source = new LinkedHashMap<>();

                            source.put("id", id);
                            source.put("search_version", version);
                            source.put(
                                    "deleted_at",
                                    format(rs.getTimestamp("deleted_at"))
                            );

                            if (rs.getTimestamp("deleted_at") == null) {
                                source.put(
                                        "category_id",
                                        Long.toString(
                                                rs.getLong("category_id")
                                        )
                                );
                                source.put("name", rs.getString("name"));
                                source.put("brand", rs.getString("brand"));
                                source.put(
                                        "description",
                                        rs.getString("description")
                                );
                                source.put(
                                        "price",
                                        rs.getBigDecimal("price")
                                                .longValueExact()
                                );
                                source.put(
                                        "quantity",
                                        rs.getInt("quantity")
                                );
                                Integer views = rs.getObject("views", Integer.class);
                                if (views == null) {
                                    throw new IllegalStateException("활성 상품의 조회수 통계 행이 없습니다.");
                                }
                                source.put("views", views);
                                source.put("sales", rs.getInt("sales"));
                                source.put(
                                        "created_at",
                                        format(
                                                rs.getTimestamp("created_at")
                                        )
                                );
                                source.put(
                                        "updated_at",
                                        format(
                                                rs.getTimestamp("updated_at")
                                        )
                                );
                            }

                            return new Snapshot(id, version, source);
                        }
                );

        Map<Long, Snapshot> result = new HashMap<>();

        for (Snapshot snapshot : snapshots) {
            result.put(snapshot.productId(), snapshot);
        }

        return result;
    }

    private static String format(Timestamp value) {
        return value == null
                ? null
                : value.toLocalDateTime()
                .atOffset(ZoneOffset.ofHours(9))
                .toInstant()
                .toString();
    }

    public record Snapshot(
            long productId,
            long version,
            Map<String, Object> source
    ) {
        public static Snapshot physicallyDeleted(long productId) {
            // 물리 삭제한 상품 ID는 재사용하지 않는다.
            // 삭제 표시는 늦은 갱신보다 우선한다.
            return new Snapshot(
                    productId,
                    Long.MAX_VALUE,
                    Map.of(
                            "id", productId,
                            "search_version", Long.MAX_VALUE,
                            "deleted_at", "1970-01-01T00:00:00Z"
                    )
            );
        }
    }
}
