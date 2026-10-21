package com.gift.gift.domain.auth.service;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.service.AuthenticationDataCleanupTransactionService;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties =
                "spring.jpa.hibernate.ddl-auto=validate"
)
class AuthenticationDataCleanupTransactionServiceTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(
                    2026,
                    9,
                    23,
                    3,
                    0
            );

    private static final String IDENTIFIER_HASH =
            "a".repeat(64);

    @Autowired
    private AuthenticationDataCleanupTransactionService
            transactionService;

    @Autowired
    private LoginIpRateLimitRepository
            loginIpRateLimitRepository;

    @Autowired
    private LoginEmailFailureLimitRepository emailRepository;

    @Autowired
    private PlatformTransactionManager
            transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        loginIpRateLimitRepository.deleteAll();
        emailRepository.deleteAll();
    }

    @Test
    @DisplayName("삭제 배치는 외부 트랜잭션 롤백과 무관하게 독립 커밋된다")
    void deleteBatch_commitsInRequiresNewTransaction() {
        LocalDateTime inactiveBefore =
                NOW.minusHours(24);

        insertIpLimit(
                inactiveBefore.minusSeconds(1)
        );

        TransactionTemplate outerTransaction =
                new TransactionTemplate(
                        transactionManager
                );

        outerTransaction.executeWithoutResult(status -> {
            AuthenticationDataCleanupBatchResult result =
                    transactionService
                            .deleteIpRateLimitBatch(
                                    inactiveBefore,
                                    1_000
                            );

            assertThat(result.selectedCount()).isEqualTo(1);
            assertThat(result.deletedCount()).isEqualTo(1);

            status.setRollbackOnly();
        });

        Integer remaining = jdbcTemplate.queryForObject(
                """
                select count(*)
                from login_ip_rate_limits
                where identifier_hash = ?
                """,
                Integer.class,
                IDENTIFIER_HASH
        );

        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("RR 후보 조회 후 별도 트랜잭션이 갱신한 IP 행은 삭제하지 않는다")
    void ipDelete_preservesConcurrentCommittedUpdate() {
        LocalDateTime cutoff = NOW.minusHours(24);
        insertIpLimit(cutoff);
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        cleanup.executeWithoutResult(status -> {
            assertThat(loginIpRateLimitRepository.findInactiveCandidates(cutoff, 1_000))
                    .contains(IDENTIFIER_HASH);

            TransactionTemplate request = new TransactionTemplate(transactionManager);
            request.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            request.executeWithoutResult(requestStatus -> jdbcTemplate.update(
                    "update login_ip_rate_limits set updated_at = ? where identifier_hash = ?",
                    NOW, IDENTIFIER_HASH
            ));

            assertThat(loginIpRateLimitRepository
                    .deleteInactiveByIdentifierHash(IDENTIFIER_HASH, cutoff)).isZero();
        });
        assertThat(loginIpRateLimitRepository.existsById(IDENTIFIER_HASH)).isTrue();
    }

    @Test
    @DisplayName("RR 후보 조회 후 별도 트랜잭션이 차단한 이메일 행은 삭제하지 않는다")
    void emailDelete_preservesConcurrentCommittedBlock() {
        LocalDateTime cutoff = NOW.minusHours(24);
        jdbcTemplate.update("""
                insert into login_email_failure_limits (
                    identifier_hash, failure_count, window_started_at,
                    backoff_level, blocked_until, created_at, updated_at
                ) values (?, 0, ?, 0, null, ?, ?)
                """, IDENTIFIER_HASH, cutoff, cutoff, cutoff);
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        cleanup.executeWithoutResult(status -> {
            assertThat(emailRepository.findInactiveCandidates(cutoff, NOW, 1_000))
                    .contains(IDENTIFIER_HASH);

            TransactionTemplate request = new TransactionTemplate(transactionManager);
            request.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            request.executeWithoutResult(requestStatus -> jdbcTemplate.update(
                    "update login_email_failure_limits set blocked_until = ? where identifier_hash = ?",
                    NOW.plusMinutes(5), IDENTIFIER_HASH
            ));

            assertThat(emailRepository
                    .deleteInactiveByIdentifierHash(IDENTIFIER_HASH, cutoff, NOW)).isZero();
        });
        assertThat(emailRepository.existsById(IDENTIFIER_HASH)).isTrue();
    }

    private void insertIpLimit(
            LocalDateTime updatedAt
    ) {
        jdbcTemplate.update(
                """
                insert into login_ip_rate_limits (
                    identifier_hash,
                    available_tokens,
                    last_refilled_at,
                    created_at,
                    updated_at
                ) values (?, 10.000, ?, ?, ?)
                """,
                IDENTIFIER_HASH,
                updatedAt,
                updatedAt,
                updatedAt
        );
    }
}
