package com.gift.gift.domain.user.service;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;

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
    private PlatformTransactionManager
            transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        loginIpRateLimitRepository.deleteAll();
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
            int deleted =
                    transactionService
                            .deleteIpRateLimitBatch(
                                    inactiveBefore,
                                    1_000
                            );

            assertThat(deleted).isEqualTo(1);

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
