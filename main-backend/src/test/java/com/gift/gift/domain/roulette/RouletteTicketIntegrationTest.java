package com.gift.gift.domain.roulette;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.credit.service.SignupCreditService;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.roulette.repository.RouletteAccountRepository;
import com.gift.gift.domain.roulette.repository.RouletteParticipationDayRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RouletteTicketIntegrationTest {

    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-26T14:59:59Z");

    @Autowired private RouletteService service;
    @Autowired private CouponInitializationService couponInitializationService;
    @Autowired private SignupCreditService signupCreditService;
    @Autowired private CreditTransactionRepository creditTransactions;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private Clock clock;
    @Autowired private RouletteAccountRepository accounts;
    @Autowired private RouletteParticipationDayRepository days;

    private final List<Long> userIds = new ArrayList<>();
    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        doReturn(BEFORE_MIDNIGHT).when(clock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(clock).getZone();
        userId = newUser();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            for (String table : List.of("roulette_ticket_grants", "roulette_days",
                    "roulette_accounts", "coupon_inventories", "credit_transactions", "credit_accounts")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            }
            users.deleteById(id);
        }
    }

    @Test
    @DisplayName("동시 출석에도 참여권과 지급 기록은 한 개다")
    void attendance_concurrentlyGrantsOnce() throws Exception {
        race(List.of(() -> service.attend(userId), () -> service.attend(userId)));
        assertThat(service.status(userId).remaining()).isEqualTo(1);
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 사용자의 출석 지급이 겹쳐도 두 요청 모두 성공한다")
    void attendance_concurrentDifferentUsers() throws Exception {
        Long otherId = newUser();
        initializeDay(userId);
        initializeDay(otherId);
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return BEFORE_MIDNIGHT;
        }).when(clock).instant();

        race(List.of(() -> service.attend(userId), () -> service.attend(otherId)));

        doReturn(BEFORE_MIDNIGHT).when(clock).instant();
        for (Long id : List.of(userId, otherId)) {
            assertThat(service.status(id).remaining()).isEqualTo(1);
            assertThat(count("roulette_ticket_grants", "user_id = ?", id)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("기존 당일 출석 크레딧을 받았다면 참여권을 추가 지급하지 않는다")
    void attendance_skipsLegacyRewardDay() {
        transaction.executeWithoutResult(status -> creditTransactions.saveAndFlush(new CreditTransaction(
                userId, CreditTransactionType.DAILY_ATTENDANCE_REWARD, 100_000, 600_000,
                null, null, LocalDate.of(2026, 10, 26), "DAILY_ATTENDANCE_REWARD:" + userId + ":2026-10-26"
        )));
        assertThat(service.attend(userId).granted()).isFalse();
        assertThat(service.status(userId).remaining()).isZero();
    }

    @Test
    @DisplayName("호출자의 오래된 스냅샷에서도 이미 커밋된 선물 참여권을 중복 지급하지 않는다")
    void gifts_deduplicatesWithExistingCallerSnapshot() {
        transaction.executeWithoutResult(status -> {
            assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isZero();
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> grantGift(777)).get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new AssertionError("별도 트랜잭션의 선물 지급 실패", exception);
            }
            service.grantGift(userId, 777L);
        });
        assertThat(service.status(userId).remaining()).isEqualTo(1);
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 선물의 중복 지급은 날짜가 바뀌어도 반영하지 않는다")
    void gifts_deduplicatesAcrossDates() {
        grantGift(10);
        grantGift(10);
        nextDay();
        grantGift(10);
        assertThat(service.status(userId).giftGranted()).isZero();
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isEqualTo(1);
    }

    @Test
    @DisplayName("선물 참여권 지급은 호출자 트랜잭션 실패와 함께 롤백한다")
    void giftGrant_rollsBackWithCaller() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.grantGift(userId, 30L);
            throw new IllegalStateException("수락 후속 처리 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.status(userId).remaining()).isZero();
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("선물 참여권은 제한 없이 누적하고 하루 한 번만 차감하며 잔액을 이월한다")
    void tickets_accumulateAndCarryOver() {
        for (long gift = 1; gift <= 6; gift++) {
            grantGift(gift);
        }
        consume();
        assertThat(service.status(userId).remaining()).isEqualTo(5);
        assertThatThrownBy(this::consume).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROULETTE_DAILY_LIMIT_EXCEEDED));
        nextDay();
        assertThat(service.status(userId).remaining()).isEqualTo(5);
        assertThat(service.status(userId).dailyRemaining()).isEqualTo(1);
        consume();
        assertThat(service.status(userId).remaining()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 트랜잭션에서 연속 지급과 차감을 수행해도 변경 감지로 잔액과 집계가 보존된다")
    void tickets_preserveChangesWithinSameTransaction() {
        transaction.executeWithoutResult(status -> {
            service.grantGift(userId, 801L);
            service.grantGift(userId, 802L);
            service.consumeTicket(userId);
            service.grantGift(userId, 803L);
        });

        var result = service.status(userId);
        assertThat(result.remaining()).isEqualTo(2);
        assertThat(result.giftGranted()).isEqualTo(3);
        assertThat(result.used()).isEqualTo(1);
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isEqualTo(3);
    }

    @Test
    @DisplayName("잔액 한도를 초과한 지급은 지급 기록과 일별 집계도 함께 롤백한다")
    void giftGrant_rollsBackWhenBalanceOverflows() {
        initializeDay(userId);
        jdbc.update("UPDATE roulette_accounts SET available_tickets = ? WHERE user_id = ?", Long.MAX_VALUE, userId);

        assertThatThrownBy(() -> grantGift(804L)).isInstanceOf(IllegalStateException.class);

        assertThat(service.status(userId).remaining()).isEqualTo(Long.MAX_VALUE);
        assertThat(service.status(userId).giftGranted()).isZero();
        assertThat(count("roulette_ticket_grants", "user_id = ?", userId)).isZero();
    }

    @Test
    @DisplayName("마지막 참여권 동시 차감은 한 요청만 성공한다")
    void tickets_concurrentlyConsumeOnce() throws Exception {
        service.attend(userId);
        Callable<Object> attempt = () -> {
            try {
                consume();
                return "SUCCESS";
            } catch (BusinessException exception) {
                return exception.getErrorCode();
            }
        };
        assertThat(race(List.of(attempt, attempt)))
                .containsExactlyInAnyOrder("SUCCESS", ErrorCode.ROULETTE_TICKET_NOT_AVAILABLE);
        assertThat(service.status(userId).remaining()).isZero();
        assertThat(service.status(userId).used()).isEqualTo(1);
    }

    @Test
    @DisplayName("차감은 상위 트랜잭션이 필요하고 후속 실패 시 잔액과 사용량을 복구한다")
    void tickets_rollBackWithCaller() {
        service.attend(userId);
        assertThatThrownBy(() -> service.consumeTicket(userId))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            service.consumeTicket(userId);
            throw new IllegalStateException("보상 처리 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(service.status(userId).remaining()).isEqualTo(1);
        assertThat(service.status(userId).used()).isZero();
    }

    @Test
    @DisplayName("조회는 지급하지 않고 출석 API는 인증된 본인에게 한 번만 지급한다")
    void api_validatesAuthenticationAndAttendance() throws Exception {
        mvc.perform(get("/roulette")).andExpect(status().isUnauthorized());
        mvc.perform(post("/roulette/attendance")).andExpect(status().isUnauthorized());
        mvc.perform(get("/roulette").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.remaining").value(0));
        mvc.perform(post("/roulette/attendance").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.granted").value(true));
        mvc.perform(post("/roulette/attendance").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.granted").value(false))
                .andExpect(jsonPath("$.data.remaining").value(1));
    }

    private void consume() {
        transaction.executeWithoutResult(status -> service.consumeTicket(userId));
    }

    private void initializeDay(Long id) {
        transaction.executeWithoutResult(status -> {
            accounts.insertIfAbsent(id);
            days.insertIfAbsent(id, LocalDate.of(2026, 10, 26));
        });
    }

    private Long newUser() {
        Long id = transaction.execute(status -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "룰렛사용자", LocalDate.of(2000, 1, 1)));
            couponInitializationService.initialize(user.getId());
            signupCreditService.grant(user.getId());
            return user.getId();
        });
        userIds.add(id);
        return id;
    }

    private void grantGift(long giftId) {
        transaction.executeWithoutResult(status -> service.grantGift(userId, giftId));
    }

    private void nextDay() {
        doReturn(BEFORE_MIDNIGHT.plusSeconds(1)).when(clock).instant();
    }

    private long count(String table, String condition, Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + condition, Long.class, id);
    }

    private List<Object> race(List<Callable<Object>> actions) throws Exception {
        CountDownLatch ready = new CountDownLatch(actions.size());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(actions.size());
        try {
            var futures = actions.stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("동시 실행 대기 시간 초과");
                }
                return action.call();
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

}
