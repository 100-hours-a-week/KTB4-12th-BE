package com.gift.gift.domain.randombox;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.randombox.entity.RandomBoxStatus;
import com.gift.gift.domain.randombox.repository.RandomBoxTicketRepository;
import com.gift.gift.domain.randombox.service.RandomBoxService;
import com.gift.gift.domain.roulette.entity.RouletteRewardType;
import com.gift.gift.domain.roulette.service.RouletteService;
import com.gift.gift.domain.roulette.service.RouletteSpinService;
import com.gift.gift.domain.roulette.support.RouletteDraw;
import com.gift.gift.domain.roulette.support.RoulettePolicy;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RandomBoxIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-27T03:00:00.123456789Z");

    @Autowired private RouletteSpinService spins;
    @Autowired private RouletteService roulette;
    @Autowired private RandomBoxService boxes;
    @Autowired private RandomBoxTicketRepository boxTickets;
    @Autowired private CouponInitializationService coupons;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private RouletteDraw draw;
    @MockitoSpyBean private Clock clock;

    private final List<Long> userIds = new ArrayList<>();
    private TransactionTemplate transaction;
    private Long userId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        doReturn(NOW).when(clock).instant();
        doReturn(RoulettePolicy.SEGMENTS.get(5)).when(draw).draw();
        userId = newUser();
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_reject_box_open");
        jdbc.update("DELETE FROM random_box_tickets");
        jdbc.update("DELETE FROM random_box_prizes");
        jdbc.update("DELETE FROM random_box_campaigns");
        for (Long id : userIds) {
            for (String table : List.of("roulette_spins", "roulette_ticket_grants", "roulette_days",
                    "roulette_accounts", "coupon_inventories")) {
                jdbc.update("DELETE FROM " + table + " WHERE user_id = ?", id);
            }
            users.deleteById(id);
        }
    }

    @Test
    @DisplayName("독립 박스 재고가 있어도 룰렛 확률과 결과는 쿠폰·꽝으로 고정되고 박스를 배정하지 않는다")
    void inventory_neverChangesRouletteRewards() {
        var before = roulette.status(userId).segments();
        inventory(true);
        assertThat(roulette.status(userId).segments()).isEqualTo(before);
        assertThat(before).extracting(RoulettePolicy.Segment::rewardType)
                .containsOnly(RouletteRewardType.COUPON, RouletteRewardType.MISS);
        roulette.attend(userId);
        var result = spins.spin(userId, UUID.randomUUID().toString());
        assertThat(result.rewardType()).isEqualTo(RouletteRewardType.MISS);
        assertThat(result.segmentId()).isEqualTo(5);
        assertThat(result.remaining()).isZero();
        assertThat(assignedCount()).isZero();
    }

    @Test
    @DisplayName("스핀 없이 보유한 당첨 박스도 개봉 전 결과를 숨기고 최초 개봉 시각을 보존한다")
    void winningBox_worksWithoutSpinAndHidesPrizeUntilOpened() throws Exception {
        inventory(true);
        Long boxId = assignFixture(1, userId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roulette_spins WHERE user_id = ?", Long.class, userId))
                .isZero();
        var hidden = boxes.detail(userId, boxId);
        assertThat(hidden.status()).isEqualTo(RandomBoxStatus.UNOPENED);
        assertThat(hidden.prizeName()).isNull();
        assertThat(hidden.voucherCode()).isNull();
        assertThat(hidden.openedAt()).isNull();
        mvc.perform(get("/random-boxes/{id}", boxId).with(jwt().jwt(t -> t.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("UNOPENED"))
                .andExpect(jsonPath("$.data.voucherCode").isEmpty());
        var opened = boxes.open(userId, boxId);
        assertThat(opened.status()).isEqualTo(RandomBoxStatus.WON);
        assertThat(opened.prizeName()).isEqualTo("테스트 경품 1");
        assertThat(opened.voucherCode()).isEqualTo("TEST-ONLY-1");
        assertThat(opened.openedAt().getNano()).isEqualTo(123456000);
        assertThat(opened.acquiredAt()).isEqualTo(opened.openedAt());
        assertThat(opened.toString()).doesNotContain("TEST-ONLY-1");
        doReturn(NOW.plusSeconds(120)).when(clock).instant();
        assertThat(boxes.open(userId, boxId)).isEqualTo(opened);
        assertThat(boxes.detail(userId, boxId)).isEqualTo(opened);
    }

    @Test
    @DisplayName("꽝 박스의 반복·동시 개봉은 최초 개봉 시각과 동일 결과를 유지한다")
    void missBox_opensConcurrentlyOnce() throws Exception {
        inventory(true);
        Long boxId = assignFixture(16, userId);
        assertThat(boxes.detail(userId, boxId).status()).isEqualTo(RandomBoxStatus.UNOPENED);
        var results = race(List.of(() -> boxes.open(userId, boxId), () -> boxes.open(userId, boxId)));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        var opened = boxes.detail(userId, boxId);
        assertThat(opened.status()).isEqualTo(RandomBoxStatus.MISS);
        assertThat(opened.openedAt()).isNotNull();
        assertThat(opened.voucherCode()).isNull();
        assertThat(opened.prizeName()).isNull();
    }

    @Test
    @DisplayName("다른 사용자와 미배정·없는 박스는 동일한 404이며 미인증은 401이다")
    void api_enforcesOwnershipAndAuthentication() throws Exception {
        inventory(true);
        Long boxId = assignFixture(1, userId);
        Long other = newUser();
        Long unassigned = jdbc.queryForObject("SELECT MIN(id) FROM random_box_tickets WHERE assigned_user_id IS NULL",
                Long.class);
        for (Long id : List.of(boxId, unassigned, Long.MAX_VALUE)) {
            mvc.perform(get("/random-boxes/{id}", id).with(jwt().jwt(t -> t.subject(other.toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RANDOM_BOX_NOT_FOUND"));
            mvc.perform(post("/random-boxes/{id}/open", id).with(jwt().jwt(t -> t.subject(other.toString()))))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.error.code").value("RANDOM_BOX_NOT_FOUND"));
        }
        mvc.perform(get("/random-boxes/{id}", boxId)).andExpect(status().isUnauthorized());
        mvc.perform(post("/random-boxes/{id}/open", boxId)).andExpect(status().isUnauthorized());
        mvc.perform(get("/random-boxes/0").with(jwt().jwt(t -> t.subject(userId.toString()))))
                .andExpect(status().isBadRequest());
        assertThat(boxes.detail(userId, boxId).status()).isEqualTo(RandomBoxStatus.UNOPENED);
    }

    @Test
    @DisplayName("별도 보유 박스는 룰렛 참여 없이 조회와 개봉할 수 있다")
    void withoutRouletteParticipation_allowsExistingBoxAccess() throws Exception {
        inventory(true);
        Long boxId = assignFixture(1, userId);
        mvc.perform(post("/random-boxes/{id}/open", boxId).with(jwt().jwt(t -> t.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.status").value("WON"))
                .andExpect(jsonPath("$.data.voucherCode").value("TEST-ONLY-1"));
        mvc.perform(get("/random-boxes/{id}", boxId).with(jwt().jwt(t -> t.subject(userId.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("WON"));
    }

    @Test
    @DisplayName("개봉 DB 실패는 개봉 상태를 롤백하고 재시도할 수 있다")
    void openFailure_preservesUnopenedBox() {
        inventory(true);
        Long boxId = assignFixture(1, userId);
        jdbc.execute("""
                CREATE TRIGGER test_reject_box_open BEFORE UPDATE ON random_box_tickets
                FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test open unavailable'
                """);
        assertThatThrownBy(() -> boxes.open(userId, boxId)).isInstanceOf(RuntimeException.class);
        assertThat(boxes.detail(userId, boxId).status()).isEqualTo(RandomBoxStatus.UNOPENED);
        assertThat(boxes.detail(userId, boxId).openedAt()).isNull();
        jdbc.execute("DROP TRIGGER test_reject_box_open");
        assertThat(boxes.open(userId, boxId).status()).isEqualTo(RandomBoxStatus.WON);
    }

    @Test
    @DisplayName("DB 제약은 교환권 중복·부분 배정·존재하지 않는 소유자를 차단한다")
    void constraints_rejectInvalidInventoryAndOwnership() {
        inventory(true);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM random_box_tickets", Long.class)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(prize_id) FROM random_box_tickets", Long.class)).isEqualTo(15);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO random_box_prizes(name, voucher_code, voucher_hash)
                SELECT name, voucher_code, voucher_hash FROM random_box_prizes LIMIT 1
                """)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_random_box_voucher_hash");
        assertThatThrownBy(() -> jdbc.update("UPDATE random_box_tickets SET opened_at = NOW() WHERE slot_number = 1"))
                .isInstanceOfSatisfying(UncategorizedSQLException.class,
                        error -> assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("chk_random_box_assignment");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE random_box_tickets SET assigned_user_id = ?, assigned_at = NOW() WHERE slot_number = 1
                """, Long.MAX_VALUE)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_random_box_owner");
        assertThatThrownBy(() -> jdbc.update("UPDATE random_box_tickets SET assigned_user_id = ? WHERE slot_number = 1",
                userId)).isInstanceOfSatisfying(UncategorizedSQLException.class,
                        error -> assertThat(error.getSQLException().getErrorCode()).isEqualTo(3819))
                .hasMessageContaining("chk_random_box_assignment");
    }

    // 별도 지급 정책은 미정이므로 보유 상태만 테스트 트랜잭션에서 준비한다.
    private Long assignFixture(int slotNumber, Long owner) {
        return transaction.execute(status -> {
            Long id = jdbc.queryForObject("SELECT id FROM random_box_tickets WHERE slot_number = ?", Long.class,
                    slotNumber);
            var ticket = boxTickets.findById(id).orElseThrow();
            ticket.assign(owner, LocalDateTime.ofInstant(NOW, RoulettePolicy.ZONE));
            return id;
        });
    }

    private void inventory(boolean ready) {
        transaction.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO random_box_campaigns(id, ready) VALUES (1, FALSE)");
            for (int i = 1; i <= 15; i++) {
                jdbc.update("INSERT INTO random_box_prizes(name, voucher_code, voucher_hash) VALUES (?, ?, SHA2(?, 256))",
                        "테스트 경품 " + i, "TEST-ONLY-" + i, "TEST-ONLY-" + i);
            }
            var prizes = jdbc.queryForList("SELECT id FROM random_box_prizes ORDER BY id", Long.class);
            for (int i = 1; i <= 100; i++) {
                jdbc.update("INSERT INTO random_box_tickets(campaign_id, slot_number, prize_id) VALUES (1, ?, ?)",
                        i, i <= 15 ? prizes.get(i - 1) : null);
            }
            jdbc.update("UPDATE random_box_campaigns SET ready = ? WHERE id = 1", ready);
        });
    }

    private long assignedCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM random_box_tickets WHERE assigned_user_id IS NOT NULL",
                Long.class);
    }

    private Long newUser() {
        Long id = transaction.execute(status -> {
            User user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "$2a$10$" + "a".repeat(53),
                    "박스사용자", LocalDate.of(2000, 1, 1)));
            coupons.initialize(user.getId());
            return user.getId();
        });
        userIds.add(id);
        return id;
    }

    private List<Object> race(List<Callable<Object>> actions) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(actions.size());
        var executor = Executors.newFixedThreadPool(actions.size());
        try {
            var futures = actions.stream().map(action -> executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return action.call();
            })).toList();
            List<Object> results = new ArrayList<>();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }
}
