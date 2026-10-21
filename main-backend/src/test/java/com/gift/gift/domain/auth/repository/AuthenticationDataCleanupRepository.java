package com.gift.gift.domain.auth.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.repository.UserSessionRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        properties =
                "spring.jpa.hibernate.ddl-auto=validate"
)
@Transactional
class AuthenticationDataCleanupRepositoryTest {

    private static final LocalDateTime NOW =
            LocalDateTime.of(
                    2026,
                    9,
                    23,
                    3,
                    0
            );

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private LoginIpRateLimitRepository
            loginIpRateLimitRepository;

    @Autowired
    private LoginEmailFailureLimitRepository
            loginEmailFailureLimitRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanTables() {
        userSessionRepository.deleteAllInBatch();
        loginIpRateLimitRepository.deleteAllInBatch();
        loginEmailFailureLimitRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("만료 또는 폐기 후 30일이 지난 세션을 최대 배치 크기만큼 삭제한다")
    void deleteInactiveSessionBatch_appliesRetentionAndBatchSize() {
        User user = saveUser();
        LocalDateTime cutoff = NOW.minusDays(30);

        long oldExpired = insertSession(
                user.getId(),
                hash('a'),
                cutoff.minusDays(10),
                cutoff.minusSeconds(1),
                null
        );

        long boundaryExpired = insertSession(
                user.getId(),
                hash('b'),
                cutoff.minusDays(10),
                cutoff,
                null
        );

        long oldRevoked = insertSession(
                user.getId(),
                hash('c'),
                cutoff.minusDays(10),
                NOW.plusDays(1),
                cutoff.minusSeconds(1)
        );

        long recentExpired = insertSession(
                user.getId(),
                hash('d'),
                cutoff.minusDays(1),
                cutoff.plusSeconds(1),
                null
        );

        long active = insertSession(
                user.getId(),
                hash('e'),
                NOW.minusDays(1),
                NOW.plusDays(13),
                null
        );

        int firstDeleted =
                userSessionRepository.deleteInactiveBatch(
                        cutoff,
                        2
                );

        assertThat(firstDeleted).isEqualTo(2);
        assertThat(sessionExists(oldExpired)).isFalse();
        assertThat(sessionExists(boundaryExpired)).isFalse();
        assertThat(sessionExists(oldRevoked)).isTrue();

        int secondDeleted =
                userSessionRepository.deleteInactiveBatch(
                        cutoff,
                        2
                );

        assertThat(secondDeleted).isEqualTo(1);
        assertThat(sessionExists(oldRevoked)).isFalse();
        assertThat(sessionExists(recentExpired)).isTrue();
        assertThat(sessionExists(active)).isTrue();
    }

    @Test
    @DisplayName("24시간 이상 비활성인 IP 제한 데이터만 배치 삭제한다")
    void deleteInactiveIpBatch_appliesInactiveBoundary() {
        LocalDateTime cutoff = NOW.minusHours(24);

        insertIpLimit(hash('i'), cutoff.minusSeconds(1));
        insertIpLimit(hash('j'), cutoff);
        insertIpLimit(hash('k'), cutoff.plusSeconds(1));

        List<String> first = loginIpRateLimitRepository.findInactiveCandidates(cutoff, 1);
        assertThat(first).containsExactly(hash('i'));
        assertThat(loginIpRateLimitRepository.deleteInactiveByIdentifierHash(first.getFirst(), cutoff))
                .isEqualTo(1);

        List<String> second = loginIpRateLimitRepository.findInactiveCandidates(cutoff, 1);
        assertThat(second).containsExactly(hash('j'));
        assertThat(loginIpRateLimitRepository.deleteInactiveByIdentifierHash(second.getFirst(), cutoff))
                .isEqualTo(1);
        assertThat(loginIpRateLimitRepository.findInactiveCandidates(cutoff, 1)).isEmpty();

        assertThat(ipLimitExists(hash('k'))).isTrue();
    }

    @Test
    @DisplayName("차단이 종료되고 24시간 비활성인 이메일 제한 데이터만 삭제한다")
    void deleteInactiveEmailBatch_preservesActiveBlock() {
        LocalDateTime cutoff = NOW.minusHours(24);

        insertEmailLimit(
                hash('l'),
                cutoff.minusSeconds(1),
                null
        );

        insertEmailLimit(
                hash('m'),
                cutoff.minusSeconds(1),
                NOW.minusSeconds(1)
        );

        insertEmailLimit(
                hash('n'),
                cutoff.minusSeconds(1),
                NOW.plusMinutes(1)
        );

        insertEmailLimit(
                hash('o'),
                cutoff.plusSeconds(1),
                null
        );

        List<String> candidates = loginEmailFailureLimitRepository
                .findInactiveCandidates(cutoff, NOW, 1_000);
        assertThat(candidates).containsExactly(hash('l'), hash('m'));
        int deleted = candidates.stream().mapToInt(identifier ->
                loginEmailFailureLimitRepository.deleteInactiveByIdentifierHash(identifier, cutoff, NOW)
        ).sum();

        assertThat(deleted).isEqualTo(2);
        assertThat(emailLimitExists(hash('l')))
                .isFalse();
        assertThat(emailLimitExists(hash('m')))
                .isFalse();
        assertThat(emailLimitExists(hash('n')))
                .isTrue();
        assertThat(emailLimitExists(hash('o')))
                .isTrue();
    }

    @Test
    @DisplayName("로그인 제한 정리 조건에 사용하는 인덱스가 존재한다")
    void cleanupIndexes_exist() {
        assertThat(indexExists(
                "login_ip_rate_limits",
                "idx_login_ip_rate_limits_updated_at"
        )).isTrue();

        assertThat(indexExists(
                "login_email_failure_limits",
                "idx_login_email_failure_limits_cleanup"
        )).isTrue();
    }

    @Test
    @DisplayName("IP 후보 조회 뒤 갱신된 행은 조건부 삭제에서 보존한다")
    void deleteIp_rechecksUpdatedAt() {
        LocalDateTime cutoff = NOW.minusHours(24);
        insertIpLimit(hash('a'), cutoff);
        assertThat(loginIpRateLimitRepository.findInactiveCandidates(cutoff, 10))
                .containsExactly(hash('a'));

        jdbcTemplate.update("update login_ip_rate_limits set updated_at = ? where identifier_hash = ?",
                NOW, hash('a'));

        assertThat(loginIpRateLimitRepository.deleteInactiveByIdentifierHash(hash('a'), cutoff))
                .isZero();
        assertThat(ipLimitExists(hash('a'))).isTrue();
    }

    @Test
    @DisplayName("이메일 후보 조회 뒤 갱신된 행은 조건부 삭제에서 보존한다")
    void deleteEmail_rechecksUpdatedAt() {
        LocalDateTime cutoff = NOW.minusHours(24);
        insertEmailLimit(hash('a'), cutoff, null);
        assertThat(loginEmailFailureLimitRepository.findInactiveCandidates(cutoff, NOW, 10))
                .containsExactly(hash('a'));

        jdbcTemplate.update("update login_email_failure_limits set updated_at = ? where identifier_hash = ?",
                NOW, hash('a'));

        assertThat(loginEmailFailureLimitRepository
                .deleteInactiveByIdentifierHash(hash('a'), cutoff, NOW)).isZero();
        assertThat(emailLimitExists(hash('a'))).isTrue();
    }

    @Test
    @DisplayName("이메일 후보 조회 뒤 차단이 시작되면 오래된 updated_at이어도 보존한다")
    void deleteEmail_rechecksBlockedUntil() {
        LocalDateTime cutoff = NOW.minusHours(24);
        insertEmailLimit(hash('a'), cutoff, null);
        assertThat(loginEmailFailureLimitRepository.findInactiveCandidates(cutoff, NOW, 10))
                .containsExactly(hash('a'));

        jdbcTemplate.update("update login_email_failure_limits set blocked_until = ? where identifier_hash = ?",
                NOW.plusMinutes(5), hash('a'));

        assertThat(loginEmailFailureLimitRepository
                .deleteInactiveByIdentifierHash(hash('a'), cutoff, NOW)).isZero();
        assertThat(emailLimitExists(hash('a'))).isTrue();
    }

    @Test
    @DisplayName("이메일 차단 종료 경계는 삭제하고 이미 사라진 후보는 0건 처리한다")
    void deleteEmail_handlesBlockBoundaryAndMissingCandidate() {
        LocalDateTime cutoff = NOW.minusHours(24);
        insertEmailLimit(hash('a'), cutoff, NOW);
        assertThat(loginEmailFailureLimitRepository.findInactiveCandidates(cutoff, NOW, 1))
                .containsExactly(hash('a'));
        assertThat(loginEmailFailureLimitRepository
                .deleteInactiveByIdentifierHash(hash('a'), cutoff, NOW)).isEqualTo(1);
        assertThat(loginEmailFailureLimitRepository
                .deleteInactiveByIdentifierHash(hash('a'), cutoff, NOW)).isZero();
    }

    private User saveUser() {
        String suffix =
                UUID.randomUUID().toString();

        return userRepository.saveAndFlush(
                new User(
                        "cleanup-" + suffix + "@example.com",
                        "$2a$12$" + "a".repeat(53),
                        "정리테스트",
                        LocalDate.of(2000, 1, 1)
                )
        );
    }

    private long insertSession(
            Long userId,
            String refreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt,
            LocalDateTime revokedAt
    ) {
        jdbcTemplate.update(
                """
                insert into user_sessions (
                    user_id,
                    refresh_token,
                    authenticated_at,
                    expires_at,
                    revoked_at,
                    deleted_at,
                    created_at,
                    updated_at
                ) values (?, ?, ?, ?, ?, null, ?, ?)
                """,
                userId,
                refreshTokenHash,
                authenticatedAt,
                expiresAt,
                revokedAt,
                authenticatedAt,
                authenticatedAt
        );

        return jdbcTemplate.queryForObject(
                """
                select id
                from user_sessions
                where refresh_token = ?
                """,
                Long.class,
                refreshTokenHash
        );
    }

    private void insertIpLimit(
            String identifierHash,
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
                identifierHash,
                updatedAt,
                updatedAt,
                updatedAt
        );
    }

    private void insertEmailLimit(
            String identifierHash,
            LocalDateTime updatedAt,
            LocalDateTime blockedUntil
    ) {
        jdbcTemplate.update(
                """
                insert into login_email_failure_limits (
                    identifier_hash,
                    failure_count,
                    window_started_at,
                    backoff_level,
                    blocked_until,
                    created_at,
                    updated_at
                ) values (?, 0, ?, 0, ?, ?, ?)
                """,
                identifierHash,
                updatedAt,
                blockedUntil,
                updatedAt,
                updatedAt
        );
    }

    private boolean sessionExists(long sessionId) {
        return count(
                "user_sessions",
                "id",
                sessionId
        ) == 1;
    }

    private boolean ipLimitExists(
            String identifierHash
    ) {
        return count(
                "login_ip_rate_limits",
                "identifier_hash",
                identifierHash
        ) == 1;
    }

    private boolean emailLimitExists(
            String identifierHash
    ) {
        return count(
                "login_email_failure_limits",
                "identifier_hash",
                identifierHash
        ) == 1;
    }

    private int count(
            String table,
            String column,
            Object value
    ) {
        return jdbcTemplate.queryForObject(
                """
                select count(*)
                from %s
                where %s = ?
                """.formatted(table, column),
                Integer.class,
                value
        );
    }

    private boolean indexExists(
            String table,
            String index
    ) {
        Integer count = jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.statistics
                where table_schema = database()
                    and table_name = ?
                    and index_name = ?
                """,
                Integer.class,
                table,
                index
        );

        return count != null && count > 0;
    }

    private String hash(char value) {
        return String.valueOf(value).repeat(64);
    }
}
