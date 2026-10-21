package com.gift.gift.domain.product.search;

import java.util.List;
import java.util.UUID;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public List<Job> claim(ProductSearchSyncProperties settings) {
        List<Job> expired = jdbcTemplate.query("""
                SELECT id, product_id, product_version, claim_token
                FROM product_search_outbox
                WHERE status = 'PROCESSING'
                  AND lease_until <= CURRENT_TIMESTAMP(6)
                ORDER BY lease_until, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> new Job(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getLong("product_version"),
                        rs.getString("claim_token")
                ),
                settings.batchSize()
        );

        for (Job job : expired) {
            updateFailure(
                    job,
                    true,
                    "LeaseExpired",
                    settings,
                    true
            );
        }

        String token = UUID.randomUUID().toString();

        List<Job> jobs = jdbcTemplate.query("""
                SELECT id, product_id, product_version
                FROM product_search_outbox
                WHERE status = 'PENDING'
                  AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                ORDER BY next_attempt_at, id
                LIMIT ?
                FOR UPDATE SKIP LOCKED
                """,
                (rs, row) -> new Job(
                        rs.getLong("id"),
                        rs.getLong("product_id"),
                        rs.getLong("product_version"),
                        token
                ),
                settings.batchSize()
        );

        for (Job job : jobs) {
            jdbcTemplate.update("""
                    UPDATE product_search_outbox
                    SET status = 'PROCESSING',
                        claim_token = ?,
                        lease_until = TIMESTAMPADD(
                            SECOND, ?, CURRENT_TIMESTAMP(6)
                        ),
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ?
                    """,
                    token,
                    settings.leaseSeconds(),
                    job.id()
            );
        }

        return jobs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(List<Job> jobs) {
        for (Job job : jobs) {
            jdbcTemplate.update("""
                    UPDATE product_search_outbox
                    SET status = 'PROCESSED',
                        processed_at = CURRENT_TIMESTAMP(6),
                        claim_token = NULL,
                        lease_until = NULL,
                        last_error_type = NULL,
                        updated_at = CURRENT_TIMESTAMP(6)
                    WHERE id = ?
                      AND status = 'PROCESSING'
                      AND claim_token = ?
                      AND lease_until > CURRENT_TIMESTAMP(6)
                    """,
                    job.id(),
                    job.claimToken()
            );
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(
            List<Job> jobs,
            boolean retryable,
            String errorType,
            ProductSearchSyncProperties settings
    ) {
        for (Job job : jobs) {
            updateFailure(
                    job,
                    retryable,
                    errorType,
                    settings,
                    false
            );
        }
    }

    private void updateFailure(
            Job job,
            boolean retryable,
            String errorType,
            ProductSearchSyncProperties settings,
            boolean recovering
    ) {
        jdbcTemplate.update("""
                UPDATE product_search_outbox
                SET status = CASE
                        WHEN ? AND failure_count + 1 < ?
                            THEN 'PENDING'
                        ELSE 'FAILED'
                    END,
                    next_attempt_at = TIMESTAMPADD(
                        SECOND,
                        LEAST(
                            ?,
                            ? * POWER(2, LEAST(failure_count, 20))
                        ),
                        CURRENT_TIMESTAMP(6)
                    ),
                    failure_count = failure_count + 1,
                    last_error_type = ?,
                    claim_token = NULL,
                    lease_until = NULL,
                    updated_at = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND claim_token = ?
                  AND (? OR lease_until > CURRENT_TIMESTAMP(6))
                """,
                retryable,
                settings.maxFailures(),
                settings.retryMaxSeconds(),
                settings.retryBaseSeconds(),
                errorType,
                job.id(),
                job.claimToken(),
                recovering
        );
    }

    public record Job(
            long id,
            long productId,
            long productVersion,
            String claimToken
    ) {
    }
}
