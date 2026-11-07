package com.gift.gift.domain.recommendation.profiling.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.profiling.support.ProfilingPublishPolicy;

@Repository
@RequiredArgsConstructor
public class ProfilingPublishStore {

    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true, timeout = 5)
    public List<PublishBacklog> findPublishBacklog() {
        return jdbc.query("""
                SELECT publish_status, COUNT(*) AS job_count, MIN(created_at) AS oldest_created_at
                FROM profiling_jobs
                WHERE result_status = 'WAITING'
                  AND publish_status IN ('READY', 'RETRY_WAIT', 'CLAIMED', 'BLOCKED')
                GROUP BY publish_status
                """, (rs, index) -> new PublishBacklog(
                rs.getString("publish_status"), rs.getLong("job_count"),
                rs.getObject("oldest_created_at", LocalDateTime.class)));
    }

    public record PublishBacklog(String publishStatus, long count, LocalDateTime oldestCreatedAt) {
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, index) ->
            new Row(
                    rs.getString("request_id"),
                    rs.getLong("source_version"),
                    rs.getString("request_body"),
                    rs.getString("publish_status"),
                    rs.getInt("attempt_count"),
                    rs.getObject(
                            "first_failed_at",
                            LocalDateTime.class
                    ),
                    rs.getObject(
                            "next_attempt_at",
                            LocalDateTime.class
                    ),
                    rs.getString("claim_token"),
                    rs.getObject(
                            "claimed_at",
                            LocalDateTime.class
                    )
            );

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public Optional<Claim> claimOne() {
        // 종료 처리·만료 회수로 한 호출이 무한 반복되지 않도록 제한한다.
        for (int i = 0; i < 100; i++) {
            List<Row> candidates = jdbc.query("""
                    SELECT *
                    FROM profiling_jobs
                    WHERE result_status = 'WAITING'
                      AND (
                        publish_status = 'READY'
                        OR (
                            publish_status = 'RETRY_WAIT'
                            AND next_attempt_at <= UTC_TIMESTAMP(6)
                        )
                        OR (
                            publish_status = 'CLAIMED'
                            AND claimed_at <=
                                TIMESTAMPADD(SECOND, -120, UTC_TIMESTAMP(6))
                        )
                      )
                    ORDER BY created_at, request_id
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                    """, ROW_MAPPER);

            if (candidates.isEmpty()) {
                return Optional.empty();
            }

            Row row = candidates.getFirst();
            LocalDateTime now = databaseNow();

            if ("CLAIMED".equals(row.status())) {
                // 선점 때 이미 횟수를 소비했으므로 여기서는 증가시키지 않는다.
                failLocked(row, now, false, "CLAIM_EXPIRED");
                continue;
            }

            if (ProfilingPublishPolicy.exhausted(
                    row.attempts(),
                    row.firstFailedAt(),
                    now
            )) {
                failLocked(row, now, true, "PUBLISH_BUDGET_EXHAUSTED");
                continue;
            }

            String token = UUID.randomUUID().toString();

            jdbc.update("""
                    UPDATE profiling_jobs
                    SET publish_status = 'CLAIMED',
                        attempt_count = attempt_count + 1,
                        claim_token = ?,
                        claimed_at = ?,
                        next_attempt_at = NULL,
                        updated_at = ?
                    WHERE request_id = ?
                    """,
                    token,
                    now,
                    now,
                    row.requestId()
            );

            return Optional.of(new Claim(
                    row.requestId(),
                    row.version(),
                    row.body(),
                    token,
                    row.status(),
                    row.nextAttemptAt()
            ));
        }

        return Optional.empty();
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public boolean markPublished(Claim claim) {
        Row row = ownedRow(claim);

        if (row == null) {
            return false;
        }

        LocalDateTime now = databaseNow();

        if (leaseExpired(row, now)) {
            return false;
        }

        return jdbc.update("""
                UPDATE profiling_jobs
                SET publish_status = 'PUBLISHED',
                    published_at = ?,
                    claim_token = NULL,
                    claimed_at = NULL,
                    next_attempt_at = NULL,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    updated_at = ?
                WHERE request_id = ?
                """,
                now,
                now,
                row.requestId()
        ) == 1;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public boolean markFailed(
            Claim claim,
            boolean permanent,
            String errorCode
    ) {
        Row row = ownedRow(claim);

        if (row == null) {
            return false;
        }

        LocalDateTime now = databaseNow();

        if (leaseExpired(row, now)) {
            return false;
        }

        failLocked(row, now, permanent, errorCode);

        return true;
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public boolean releaseUnsent(Claim claim) {
        Row row = ownedRow(claim);

        if (row == null) {
            return false;
        }

        LocalDateTime now = databaseNow();

        if (leaseExpired(row, now)) {
            return false;
        }

        return jdbc.update("""
                UPDATE profiling_jobs
                SET publish_status = ?,
                    attempt_count = attempt_count - 1,
                    next_attempt_at = ?,
                    claim_token = NULL,
                    claimed_at = NULL,
                    updated_at = ?
                WHERE request_id = ?
                """,
                claim.previousStatus(),
                claim.previousNextAttemptAt(),
                now,
                row.requestId()
        ) == 1;
    }

    private Row ownedRow(Claim claim) {
        List<Row> rows = jdbc.query("""
                SELECT *
                FROM profiling_jobs
                WHERE request_id = ?
                  AND source_version = ?
                  AND publish_status = 'CLAIMED'
                  AND claim_token = ?
                  AND result_status = 'WAITING'
                FOR UPDATE
                """,
                ROW_MAPPER,
                claim.requestId(),
                claim.version(),
                claim.token()
        );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean leaseExpired(Row row, LocalDateTime now) {
        return row.claimedAt() == null
                || !now.isBefore(row.claimedAt().plusSeconds(
                ProfilingPublishPolicy.CLAIM_SECONDS
        ));
    }

    private void failLocked(
            Row row,
            LocalDateTime now,
            boolean permanent,
            String errorCode
    ) {
        LocalDateTime firstFailedAt = row.firstFailedAt() == null
                ? now
                : row.firstFailedAt();

        boolean blocked = permanent
                || ProfilingPublishPolicy.exhausted(
                row.attempts(),
                firstFailedAt,
                now
        );

        LocalDateTime nextAttemptAt = blocked
                ? null
                : now.plusNanos(
                ProfilingPublishPolicy.retryDelayMillis(
                        row.attempts()
                ) * 1_000_000
        );

        jdbc.update("""
                UPDATE profiling_jobs
                SET publish_status = ?,
                    first_failed_at = ?,
                    next_attempt_at = ?,
                    claim_token = NULL,
                    claimed_at = NULL,
                    last_error_code = ?,
                    last_error_message = ?,
                    updated_at = ?
                WHERE request_id = ?
                """,
                blocked ? "BLOCKED" : "RETRY_WAIT",
                firstFailedAt,
                nextAttemptAt,
                errorCode,
                "프로파일링 발행 실패: " + errorCode,
                now,
                row.requestId()
        );
    }

    private LocalDateTime databaseNow() {
        return jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(6)",
                LocalDateTime.class
        );
    }

    public record Claim(
            String requestId,
            long version,
            String body,
            String token,
            String previousStatus,
            LocalDateTime previousNextAttemptAt
    ) {
    }

    private record Row(
            String requestId,
            long version,
            String body,
            String status,
            int attempts,
            LocalDateTime firstFailedAt,
            LocalDateTime nextAttemptAt,
            String token,
            LocalDateTime claimedAt
    ) {
    }
}
