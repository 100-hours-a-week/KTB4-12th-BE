package com.gift.gift.domain.credit.service;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class DailyAttendanceCreditServiceIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired
    private DailyAttendanceCreditService dailyAttendanceCreditService;

    @Autowired
    private SignupCreditService signupCreditService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Clock clock;

    private final List<Long> userIds = new ArrayList<>();

    @Test
    @DisplayName("동시 접속에도 당일 크레딧과 원장을 한 번만 지급한다")
    void grant_concurrentlyRewardsOncePerDay() throws Exception {

        User user = persistUser();

        signupCreditService.grant(user.getId());

        LocalDate attendanceDate = LocalDate.now(clock);

        race(
                () -> dailyAttendanceCreditService
                        .grant(user.getId()),
                () -> dailyAttendanceCreditService
                        .grant(user.getId())
        );

        Long balance = jdbcTemplate.queryForObject(
                """
                SELECT balance
                FROM credit_accounts
                WHERE user_id = ?
                """,
                Long.class,
                user.getId()
        );

        Long transactionCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM credit_transactions
                        WHERE user_id = ?
                          AND type = 'DAILY_ATTENDANCE_REWARD'
                        """,
                        Long.class,
                        user.getId()
                );

        Date savedAttendanceDate =
                jdbcTemplate.queryForObject(
                        """
                        SELECT attendance_date
                        FROM credit_transactions
                        WHERE user_id = ?
                          AND type = 'DAILY_ATTENDANCE_REWARD'
                        """,
                        Date.class,
                        user.getId()
                );

        String deduplicationKey =
                jdbcTemplate.queryForObject(
                        """
                        SELECT deduplication_key
                        FROM credit_transactions
                        WHERE user_id = ?
                          AND type = 'DAILY_ATTENDANCE_REWARD'
                        """,
                        String.class,
                        user.getId()
                );

        assertThat(balance).isEqualTo(600_000L);

        assertThat(transactionCount).isEqualTo(1L);

        assertThat(savedAttendanceDate).isNotNull();

        assertThat(savedAttendanceDate.toLocalDate()).isEqualTo(attendanceDate);

        assertThat(deduplicationKey)
                .isEqualTo("DAILY_ATTENDANCE_REWARD:" + user.getId() + ":" + attendanceDate);
    }

    private void race(Runnable first, Runnable second) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(2);

        CountDownLatch ready = new CountDownLatch(2);

        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<?> firstResult =
                    executor.submit(
                            () -> runWhenReady(first, ready, start)
                    );

            Future<?> secondResult =
                    executor.submit(
                            () -> runWhenReady(second, ready, start)
                    );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();

            start.countDown();

            firstResult.get(30, TimeUnit.SECONDS);

            secondResult.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void runWhenReady(Runnable action, CountDownLatch ready, CountDownLatch start) {

        ready.countDown();

        try {
            if (!start.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("동시 실행 대기 시간이 초과되었습니다.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();

            throw new AssertionError("동시 실행 대기가 중단되었습니다.", exception);
        }

        action.run();
    }

    private User persistUser() {
        User savedUser =
                userRepository.saveAndFlush(
                        new User(
                                UUID.randomUUID()
                                        + "@example.com",
                                PASSWORD_HASH,
                                "일별접속사용자",
                                LocalDate.of(
                                        2000,
                                        1,
                                        1
                                )
                        )
                );

        userIds.add(savedUser.getId());

        return savedUser;
    }

    @AfterEach
    void cleanUp() {
        for (Long userId : userIds) {
            jdbcTemplate.update(
                    """
                    DELETE FROM credit_transactions
                    WHERE user_id = ?
                    """,
                    userId
            );

            jdbcTemplate.update(
                    """
                    DELETE FROM credit_accounts
                    WHERE user_id = ?
                    """,
                    userId
            );
        }

        for (Long userId : userIds) {
            jdbcTemplate.update(
                    """
                    DELETE FROM users
                    WHERE id = ?
                    """,
                    userId
            );
        }
    }
}
