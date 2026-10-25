package com.gift.gift.domain.coupon;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.dto.response.CouponResponse;
import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.coupon.service.CouponIssueService;
import com.gift.gift.domain.coupon.service.CouponQueryService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class CouponIntegrationTest {

    @Autowired
    private CouponInitializationService initializationService;

    @Autowired
    private CouponIssueService issueService;

    @Autowired
    private CouponQueryService queryService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> userIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbcTemplate.update(
                    "DELETE FROM coupon_inventories WHERE user_id = ?",
                    userId
            );
            userRepository.deleteById(userId);
        }
    }

    @Test
    @DisplayName("초기 두 행은 수량 0이며 조회 결과는 비어 있다")
    void initialize_createsTwoEmptyInventories() {
        Long userId = persistUser();

        inTransaction(() -> initializationService.initialize(userId));

        assertThat(jdbcTemplate.queryForList(
                """
                SELECT multiplier
                FROM coupon_inventories
                WHERE user_id = ?
                ORDER BY multiplier
                """,
                Integer.class,
                userId
        )).containsExactly(2, 3);

        assertThat(queryService.getCoupons(userId).coupons()).isEmpty();
        assertThat(quantity(userId, 2)).isZero();
        assertThat(quantity(userId, 3)).isZero();
    }

    @Test
    @DisplayName("본인의 양수 수량만 배수순으로 반환하고 갱신 시각을 기록한다")
    void issue_returnsOnlyOwnPositiveQuantities() {
        Long ownerId = initializedUser();
        Long strangerId = initializedUser();

        jdbcTemplate.update(
                """
                UPDATE coupon_inventories
                SET updated_at = '2000-01-01 00:00:00'
                WHERE user_id = ?
                """,
                ownerId
        );

        inTransaction(() -> {
            issueService.issue(ownerId, 3);
            issueService.issue(ownerId, 2);
            issueService.issue(ownerId, 2);
            issueService.issue(strangerId, 3);
        });

        assertThat(queryService.getCoupons(ownerId).coupons())
                .containsExactly(
                        new CouponResponse(2, 2),
                        new CouponResponse(3, 1)
                );

        LocalDateTime updatedAt = jdbcTemplate.queryForObject(
                """
                SELECT updated_at
                FROM coupon_inventories
                WHERE user_id = ? AND multiplier = 2
                """,
                LocalDateTime.class,
                ownerId
        );

        assertThat(updatedAt)
                .isAfter(LocalDateTime.of(2000, 1, 1, 0, 0));
    }

    @Test
    @DisplayName("동시 발급의 증가분이 유실되지 않는다")
    void issue_concurrentlyIncrementsWithoutLoss() throws Exception {
        Long userId = initializedUser();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            Runnable action = () -> {
                ready.countDown();

                try {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 실행 대기 시간 초과");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }

                inTransaction(() -> issueService.issue(userId, 2));
            };

            var first = executor.submit(action);
            var second = executor.submit(action);

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);

            assertThat(quantity(userId, 2)).isEqualTo(2);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("트랜잭션 없는 초기화와 발급 호출을 거부한다")
    void services_requireCallerTransaction() {
        Long userId = initializedUser();

        assertThatThrownBy(() -> issueService.issue(userId, 2))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThatThrownBy(() -> initializationService.initialize(userId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("호출자 실패 시 쿠폰 발급도 롤백한다")
    void issue_rollsBackWhenCallerFails() {
        Long userId = initializedUser();

        assertThatThrownBy(() -> inTransaction(() -> {
            issueService.issue(userId, 2);
            throw new IllegalStateException("호출자 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(quantity(userId, 2)).isZero();
    }

    @Test
    @DisplayName("발급 실패 시 앞서 저장한 호출자의 회원도 롤백한다")
    void issueFailure_rollsBackCallerChanges() {
        String email = UUID.randomUUID() + "@example.com";

        assertThatThrownBy(() -> inTransaction(() -> {
            User user = userRepository.saveAndFlush(newUser(email));
            issueService.issue(user.getId(), 2);
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.existsByEmail(email)).isFalse();
    }

    @Test
    @DisplayName("초기화 이후 가입 처리 실패 시 회원과 쿠폰 행을 롤백한다")
    void initialize_rollsBackWithUserCreation() {
        String email = UUID.randomUUID() + "@example.com";
        Long[] rolledBackId = new Long[1];

        assertThatThrownBy(() -> inTransaction(() -> {
            User user = userRepository.saveAndFlush(newUser(email));
            rolledBackId[0] = user.getId();
            initializationService.initialize(user.getId());
            throw new IllegalStateException("가입 후속 처리 실패");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(userRepository.existsByEmail(email)).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM coupon_inventories WHERE user_id = ?",
                Long.class,
                rolledBackId[0]
        )).isZero();
    }

    @Test
    @DisplayName("잘못된 입력과 수량 한도 초과를 거부한다")
    void issue_rejectsInvalidInputAndOverflow() {
        Long userId = initializedUser();

        assertThatThrownBy(() -> inTransaction(
                () -> issueService.issue(null, 2)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> inTransaction(
                () -> issueService.issue(0L, 2)
        )).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> inTransaction(
                () -> issueService.issue(userId, 4)
        )).isInstanceOf(IllegalArgumentException.class);

        jdbcTemplate.update(
                """
                UPDATE coupon_inventories
                SET quantity = 2147483647
                WHERE user_id = ? AND multiplier = 2
                """,
                userId
        );

        assertThatThrownBy(() -> inTransaction(
                () -> issueService.issue(userId, 2)
        )).isInstanceOf(IllegalStateException.class);

        assertThat(quantity(userId, 2)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("DB가 중복 배수 행과 음수 수량 및 잘못된 배수를 거부한다")
    void database_enforcesInventoryConstraints() {
        Long userId = initializedUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO coupon_inventories
                    (user_id, multiplier, quantity, created_at, updated_at)
                VALUES (?, 2, 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """,
                userId
        )).isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE coupon_inventories
                SET quantity = -1
                WHERE user_id = ? AND multiplier = 2
                """,
                userId
        )).isInstanceOf(DataAccessException.class)
                .satisfies(failure -> {
                    SQLException cause = (SQLException) ((DataAccessException) failure).getMostSpecificCause();
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("chk_coupon_inventories_quantity");
                });

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE coupon_inventories
                SET multiplier = 4
                WHERE user_id = ? AND multiplier = 3
                """,
                userId
        )).isInstanceOf(DataAccessException.class)
                .satisfies(failure -> {
                    SQLException cause = (SQLException) ((DataAccessException) failure).getMostSpecificCause();
                    assertThat(cause.getErrorCode()).isEqualTo(3819);
                    assertThat(cause.getMessage()).contains("chk_coupon_inventories_multiplier");
                });
    }

    @Test
    @DisplayName("기존 회원 보완 SQL은 누락 행만 채우고 재실행해도 보유 수량을 유지한다")
    void backfill_preservesExistingQuantitiesAndIsRepeatable() {
        Long partialUserId = initializedUser();
        Long emptyUserId = persistUser();
        inTransaction(() -> issueService.issue(partialUserId, 2));
        jdbcTemplate.update(
                "DELETE FROM coupon_inventories WHERE user_id = ? AND multiplier = 3",
                partialUserId
        );

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            for (int attempt = 0; attempt < 2; attempt++) {
                jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                            "db/migration/V38__backfill_coupon_inventories.sql"
                    ));
                    return null;
                });
            }
            assertThat(quantity(partialUserId, 2)).isEqualTo(1);
            assertThat(quantity(partialUserId, 3)).isZero();
            assertThat(quantity(emptyUserId, 2)).isZero();
            assertThat(quantity(emptyUserId, 3)).isZero();
            // 전체 회원 대상 SQL이므로 다른 테스트 사용자의 행은 남기지 않는다.
            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("하나의 트랜잭션에서 일부 발급 후 후속 발급이 실패하면 모두 롤백한다")
    void issueFailure_rollsBackEarlierIssue() {
        Long userId = initializedUser();
        assertThatThrownBy(() -> inTransaction(() -> {
            issueService.issue(userId, 2);
            issueService.issue(userId, 4);
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(quantity(userId, 2)).isZero();
    }

    @Test
    @DisplayName("수량 한도 직전에는 마지막 한 개만 발급할 수 있다")
    void issue_allowsLastQuantityBeforeLimit() {
        Long userId = initializedUser();
        jdbcTemplate.update("""
                UPDATE coupon_inventories SET quantity = ?
                WHERE user_id = ? AND multiplier = 2
                """, Integer.MAX_VALUE - 1, userId);
        inTransaction(() -> issueService.issue(userId, 2));
        assertThat(quantity(userId, 2)).isEqualTo(Integer.MAX_VALUE);
        assertThatThrownBy(() -> inTransaction(() -> issueService.issue(userId, 2)))
                .isInstanceOf(IllegalStateException.class);
    }

    private Long initializedUser() {
        Long userId = persistUser();
        inTransaction(() -> initializationService.initialize(userId));
        return userId;
    }

    private Long persistUser() {
        User user = userRepository.saveAndFlush(
                newUser(UUID.randomUUID() + "@example.com")
        );
        userIds.add(user.getId());
        return user.getId();
    }

    private User newUser(String email) {
        return new User(
                email,
                "$2a$10$" + "a".repeat(53),
                "쿠폰테스트",
                LocalDate.of(2000, 1, 1)
        );
    }

    private int quantity(Long userId, int multiplier) {
        return jdbcTemplate.queryForObject(
                """
                SELECT quantity
                FROM coupon_inventories
                WHERE user_id = ? AND multiplier = ?
                """,
                Integer.class,
                userId,
                multiplier
        );
    }

    private void inTransaction(Runnable action) {
        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> action.run());
    }
}
