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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.roulette.dto.response.RouletteSpinResponse;
import com.gift.gift.domain.roulette.entity.RouletteRewardType;
import com.gift.gift.domain.roulette.repository.RouletteAccountRepository;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.roulette.service.RouletteSpinService;
import com.gift.gift.domain.roulette.support.RouletteDraw;
import com.gift.gift.domain.roulette.support.RoulettePolicy;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RouletteSpinIntegrationTest {

    private static final Instant BEFORE_MIDNIGHT = Instant.parse("2026-10-26T14:59:59Z");

    @Autowired private RouletteSpinService spins;
    @Autowired private RouletteService tickets;
    @Autowired private CouponInitializationService couponInitializationService;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private Clock clock;
    @MockitoSpyBean private RouletteDraw draw;
    @MockitoSpyBean private RouletteAccountRepository accounts;

    private final List<Long> userIds = new ArrayList<>();
    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        doReturn(BEFORE_MIDNIGHT).when(clock).instant();
        doReturn(ZoneId.of("Asia/Seoul")).when(clock).getZone();
        doReturn(RoulettePolicy.select(0)).when(draw).draw();
        userId = newUser();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            for (String table : List.of("roulette_spins", "roulette_ticket_grants", "roulette_days",
                    "roulette_accounts", "coupon_inventories")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            }
            users.deleteById(id);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1750, 4917})
    @DisplayName("쿠폰 당첨은 참여권 한 장과 일일 한도를 소비하고 해당 배수 한 장만 지급한다")
    void spin_awardsCouponAndReplaysSameResult(int value) {
        var segment = RoulettePolicy.select(value);
        doReturn(segment).when(draw).draw();
        tickets.attend(userId);
        String key = UUID.randomUUID().toString();

        var result = spins.spin(userId, key);

        assertThat(result.segmentId()).isEqualTo(segment.segmentId());
        assertThat(result.rewardType()).isEqualTo(RouletteRewardType.COUPON);
        assertThat(result.multiplier()).isEqualTo(segment.multiplier());
        assertThat(result.rewardVersion()).isEqualTo(RoulettePolicy.VERSION);
        assertThat(result.remaining()).isZero();
        assertThat(quantity(userId, segment.multiplier())).isEqualTo(1);
        assertThat(quantity(userId, segment.multiplier() == 2 ? 3 : 2)).isZero();
        assertThat(tickets.status(userId).used()).isEqualTo(1);
        assertThat(spins.spin(userId, key.toUpperCase(java.util.Locale.ROOT))).isEqualTo(result);
        assertThat(countSpins(userId)).isEqualTo(1);
        assertThat(quantity(userId, segment.multiplier())).isEqualTo(1);
        verify(draw, times(1)).draw();
    }

    @ParameterizedTest
    @ValueSource(ints = {3250, 6667, 8334})
    @DisplayName("꽝도 참여권과 일일 한도를 소비하고 재요청에 같은 결과를 반환한다")
    void spin_persistsMissWithoutAwardingCoupon(int value) {
        doReturn(RoulettePolicy.select(value)).when(draw).draw();
        tickets.attend(userId);
        String key = UUID.randomUUID().toString();

        var result = spins.spin(userId, key);

        assertThat(result.rewardType()).isEqualTo(RouletteRewardType.MISS);
        assertThat(result.multiplier()).isNull();
        assertThat(tickets.status(userId).remaining()).isZero();
        assertThat(tickets.status(userId).used()).isEqualTo(1);
        assertThat(quantity(userId, 2) + quantity(userId, 3)).isZero();
        assertThat(spins.spin(userId, key)).isEqualTo(result);
    }

    @Test
    @DisplayName("날짜가 바뀐 재요청은 최초 잔액 스냅샷을 반환하며 새 날짜의 한도를 소비하지 않는다")
    void spin_replaysAcrossDatesAndBalanceChanges() {
        tickets.attend(userId);
        String key = UUID.randomUUID().toString();
        var original = spins.spin(userId, key);
        doReturn(BEFORE_MIDNIGHT.plusSeconds(1)).when(clock).instant();
        tickets.attend(userId);
        grantGift(userId, 501L);

        assertThat(spins.spin(userId, key)).isEqualTo(original);
        assertThat(original.remaining()).isZero();
        assertThat(tickets.status(userId).remaining()).isEqualTo(2);
        assertThat(tickets.status(userId).used()).isZero();
        var next = spins.spin(userId, UUID.randomUUID().toString());
        assertThat(next.date()).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(next.remaining()).isEqualTo(1);
        assertThat(quantity(userId, 2)).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 키 동시 실행은 모두 동일 결과를 받고 쿠폰과 참여권은 한 번만 반영한다")
    void spin_concurrentlyReplaysSameKey() throws Exception {
        tickets.attend(userId);
        String key = UUID.randomUUID().toString();
        Callable<Object> action = () -> spins.spin(userId, key);

        var results = race(List.of(action, action, action, action));

        assertThat(results).allSatisfy(result -> assertThat(result).isEqualTo(results.getFirst()));
        assertThat(countSpins(userId)).isEqualTo(1);
        assertThat(quantity(userId, 2)).isEqualTo(1);
        assertThat(tickets.status(userId).remaining()).isZero();
        assertThat(tickets.status(userId).used()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 키의 동시 실행은 잔액이 남아 있어도 하루 한 번만 성공한다")
    void spin_concurrentlyEnforcesDailyLimit() throws Exception {
        tickets.attend(userId);
        grantGift(userId, 502L);
        Callable<Object> action = () -> {
            try {
                return spins.spin(userId, UUID.randomUUID().toString());
            } catch (BusinessException exception) {
                return exception.getErrorCode();
            }
        };

        var results = race(List.of(action, action));

        assertThat(results.stream().filter(RouletteSpinResponse.class::isInstance)).hasSize(1);
        assertThat(results).contains(ErrorCode.ROULETTE_DAILY_LIMIT_EXCEEDED);
        assertThat(countSpins(userId)).isEqualTo(1);
        assertThat(quantity(userId, 2)).isEqualTo(1);
        assertThat(tickets.status(userId).remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("서로 다른 사용자는 같은 키로 동시에 추첨해도 각자 보상을 받는다")
    void spin_concurrentlyAllowsDifferentUsersWithSameKey() throws Exception {
        Long otherId = newUser();
        tickets.attend(userId);
        tickets.attend(otherId);
        String key = UUID.randomUUID().toString();
        CyclicBarrier barrier = new CyclicBarrier(2);
        doAnswer(invocation -> {
            barrier.await(10, TimeUnit.SECONDS);
            return RoulettePolicy.select(0);
        }).when(draw).draw();

        var results = race(List.of(() -> spins.spin(userId, key), () -> spins.spin(otherId, key)));

        assertThat(results.get(0)).isNotEqualTo(results.get(1));
        for (Long id : List.of(userId, otherId)) {
            assertThat(countSpins(id)).isEqualTo(1);
            assertThat(quantity(id, 2)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("자정 전 시작한 요청이 계정 잠금을 자정 후 얻으면 새 날짜 한도를 사용한다")
    void spin_usesDateAfterAcquiringAccountLock() throws Exception {
        tickets.attend(userId);
        CountDownLatch attempted = new CountDownLatch(1);
        var repositoryDelegate = mockingDetails(accounts).getMockCreationSettings().getDefaultAnswer();
        doAnswer(invocation -> {
            attempted.countDown();
            return repositoryDelegate.answer(invocation);
        }).when(accounts).insertIfAbsent(userId);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var pending = transaction.execute(status -> {
                accounts.findByUserIdForUpdate(userId).orElseThrow();
                var future = executor.submit(() -> spins.spin(userId, UUID.randomUUID().toString()));
                try {
                    assertThat(attempted.await(10, TimeUnit.SECONDS)).isTrue();
                    doReturn(BEFORE_MIDNIGHT.plusSeconds(1)).when(clock).instant();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return future;
            });
            assertThat(pending.get(30, TimeUnit.SECONDS).date()).isEqualTo(LocalDate.of(2026, 10, 27));
        }
        assertThat(tickets.status(userId).used()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used FROM roulette_days WHERE user_id = ? AND participation_date = ?",
                Integer.class, userId, LocalDate.of(2026, 10, 26))).isZero();
    }

    @Test
    @DisplayName("쿠폰 수량 한도로 발급이 실패하면 저장한 결과와 참여권·일일 사용량을 모두 롤백한다")
    void spin_rollsBackAllChangesWhenCouponCannotBeIssued() {
        tickets.attend(userId);
        jdbc.update("UPDATE coupon_inventories SET quantity = ? WHERE user_id = ? AND multiplier = 2",
                Integer.MAX_VALUE, userId);
        String key = UUID.randomUUID().toString();

        assertThatThrownBy(() -> spins.spin(userId, key)).isInstanceOf(IllegalStateException.class);

        assertThat(countSpins(userId)).isZero();
        assertThat(tickets.status(userId).remaining()).isEqualTo(1);
        assertThat(tickets.status(userId).used()).isZero();
        assertThat(quantity(userId, 2)).isEqualTo(Integer.MAX_VALUE);
        jdbc.update("UPDATE coupon_inventories SET quantity = 0 WHERE user_id = ? AND multiplier = 2", userId);
        assertThat(spins.spin(userId, key).remaining()).isZero();
        assertThat(quantity(userId, 2)).isEqualTo(1);
    }

    @Test
    @DisplayName("DB는 같은 사용자 멱등키 중복 및 잘못된 보상 조합을 각각 거부한다")
    void persistence_enforcesIdempotencyAndRewardConstraints() {
        tickets.attend(userId);
        var result = spins.spin(userId, UUID.randomUUID().toString());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO roulette_spins (user_id, idempotency_key, participation_date, segment_id,
                    reward_type, multiplier, reward_version, remaining_tickets, created_at)
                SELECT user_id, idempotency_key, participation_date, segment_id, reward_type, multiplier,
                    reward_version, remaining_tickets, created_at FROM roulette_spins WHERE id = ?
                """, result.spinId())).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_roulette_spins_user_key");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE roulette_spins SET multiplier = NULL WHERE id = ?", result.spinId()))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,
                        error -> assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("chk_roulette_spins_reward");
        assertThatThrownBy(() -> jdbc.update("UPDATE roulette_spins SET segment_id = 5 WHERE id = ?", result.spinId()))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,
                        error -> assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("chk_roulette_spins_segment");
        assertThat(countSpins(userId)).isEqualTo(1);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "not-a-uuid", "1-1-1-1-1", " 550e8400-e29b-41d4-a716-446655440000", "550e8400e29b41d4a716446655440000"
    })
    @DisplayName("키 누락·빈 값·비표준 UUID는 400이며 참여권을 소비하지 않는다")
    void api_rejectsInvalidKey(String key) throws Exception {
        tickets.attend(userId);
        var request = post("/roulette/spins").with(jwt().jwt(token -> token.subject(userId.toString())));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        mvc.perform(request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
        assertThat(tickets.status(userId).remaining()).isEqualTo(1);
        assertThat(countSpins(userId)).isZero();
    }

    @Test
    @DisplayName("추첨 API는 인증·참여권 부족·일일 제한을 구분하며 본인의 결과만 반환한다")
    void api_enforcesAuthenticationOwnershipAndLimits() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/roulette/spins").header("Idempotency-Key", key))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/roulette/spins").header("Idempotency-Key", key)
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROULETTE_TICKET_NOT_AVAILABLE"));
        tickets.attend(userId);
        grantGift(userId, 503L);
        var original = spins.spin(userId, key);
        mvc.perform(post("/roulette/spins").header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROULETTE_DAILY_LIMIT_EXCEEDED"));
        Long otherId = newUser();
        mvc.perform(post("/roulette/spins").header("Idempotency-Key", key)
                        .with(jwt().jwt(token -> token.subject(otherId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("ROULETTE_TICKET_NOT_AVAILABLE"));
        mvc.perform(post("/roulette/spins").header("Idempotency-Key", key)
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.spinId").value(original.spinId()))
                .andExpect(jsonPath("$.data.remaining").value(1))
                .andExpect(jsonPath("$.data.rewardType").value("COUPON"));
    }

    @Test
    @DisplayName("참여 현황은 실제 6칸 확률과 쿠폰·꽝 보상를 제공하고 조회만으로 기록을 만들지 않는다")
    void api_publishesProbabilitiesWithoutGrantingTickets() throws Exception {
        mvc.perform(get("/roulette").with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewardVersion").value(RoulettePolicy.VERSION))
                .andExpect(jsonPath("$.data.boxesAvailable").doesNotExist())
                .andExpect(jsonPath("$.data.segments.length()").value(6))
                .andExpect(jsonPath("$.data.segments[0].probability").value(17.5))
                .andExpect(jsonPath("$.data.segments[1].probability").value(15))
                .andExpect(jsonPath("$.data.segments[5].probability").value(16.66))
                .andExpect(jsonPath("$.data.segments[5].rewardType").value("MISS"))
                .andExpect(jsonPath("$.data.remaining").value(0));
        assertThat(countSpins(userId)).isZero();
    }

    @Test
    @DisplayName("별도 활성화 설정 없이 출석 참여권을 사용해 쿠폰을 지급한다")
    void api_spinsWithoutFeatureFlag() throws Exception {
        tickets.attend(userId);
        doReturn(RoulettePolicy.SEGMENTS.get(0)).when(draw).draw();

        mvc.perform(post("/roulette/spins").header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(jwt().jwt(token -> token.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewardType").value("COUPON"));
        assertThat(countSpins(userId)).isEqualTo(1);
        assertThat(quantity(userId, 2)).isEqualTo(1);
        assertThat(quantity(userId, 3)).isZero();
        assertThat(jdbc.queryForObject("SELECT available_tickets FROM roulette_accounts WHERE user_id = ?",
                Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("기존 coupon-v1 실행은 버전과 결과를 유지하며 새 판 정책으로 다시 추첨하지 않는다")
    void spin_replaysLegacyVersion() {
        tickets.attend(userId);
        String key = UUID.randomUUID().toString();
        var original = spins.spin(userId, key);
        jdbc.update("UPDATE roulette_spins SET reward_version = 'coupon-v1' WHERE id = ?", original.spinId());
        var replay = spins.spin(userId, key);
        assertThat(replay.spinId()).isEqualTo(original.spinId());
        assertThat(replay.rewardVersion()).isEqualTo("coupon-v1");
        assertThat(replay.segmentId()).isEqualTo(original.segmentId());
        assertThat(replay.remaining()).isEqualTo(original.remaining());
        assertThat(quantity(userId, 2)).isEqualTo(1);
        verify(draw, times(1)).draw();
    }

    private Long newUser() {
        Long id = transaction.execute(status -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53), "룰렛사용자", LocalDate.of(2000, 1, 1)));
            couponInitializationService.initialize(user.getId());
            return user.getId();
        });
        userIds.add(id);
        return id;
    }

    private void grantGift(Long id, long giftId) {
        transaction.executeWithoutResult(status -> tickets.grantGift(id, giftId));
    }

    private int quantity(Long id, int multiplier) {
        return jdbc.queryForObject("SELECT quantity FROM coupon_inventories WHERE user_id = ? AND multiplier = ?",
                Integer.class, id, multiplier);
    }

    private long countSpins(Long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM roulette_spins WHERE user_id = ?", Long.class, id);
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
