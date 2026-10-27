package com.gift.gift.domain.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.gift.gift.domain.order.service.PaymentService;
import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.infrastructure.payment.client.PaymentClient;
import com.gift.gift.infrastructure.payment.dto.PaymentResponse;
import com.gift.gift.infrastructure.payment.dto.PaymentServerStatus;
import com.gift.gift.infrastructure.payment.exception.PaymentClientException;
import com.gift.gift.infrastructure.payment.exception.PaymentClientFailure;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.repository.OrderCouponUsageRepository;
import com.gift.gift.domain.coupon.entity.OrderCouponUsageStatus;
import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.coupon.service.CouponIssueService;
import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.service.GiftAcceptanceService;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.order.service.PaymentCompletionService;
import com.gift.gift.domain.order.service.PaymentCancellationCompensationService;
import com.gift.gift.domain.order.service.PaymentCancellationRequestService;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.service.OrderApprovalTransactionService;
import com.gift.gift.domain.order.service.PaymentAutoCancellationTransactionService;
import com.gift.gift.domain.order.service.PaymentCancellationApproval;
import com.gift.gift.domain.order.service.PaymentRequestService;
import com.gift.gift.domain.order.service.PaymentQueryService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.coupon.payment-enabled=true")
@AutoConfigureMockMvc
class CouponRewardLifecycleIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private PaymentQueryService paymentQueries;

    @Autowired
    private EntityManager entityManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private CouponInitializationService initialization;
    @Autowired
    private CouponIssueService issue;
    @Autowired
    private OrderCouponService coupons;
    @Autowired
    private PaymentRequestService requests;
    @Autowired
    private OrderApprovalTransactionService approvals;
    @Autowired
    private PaymentAutoCancellationTransactionService autoCancellation;
    @Autowired
    private OrderRepository orders;
    @MockitoSpyBean
    private OrderCouponUsageRepository usages;

    @Autowired private PaymentCompletionService completions;
    @Autowired private GiftAcceptanceService acceptances;
    @Autowired private PaymentCancellationCompensationService compensation;
    @Autowired private PaymentCancellationRequestService cancellationRequests;
    @Autowired private com.gift.gift.domain.order.service.PaymentCancellationTransactionService cancellationTransactions;
    @MockitoSpyBean private CreditTransactionRepository ledger;

    @Autowired private PaymentService payments;
    @Autowired private com.gift.gift.domain.order.service.PaymentCancellationService cancellationService;
    @MockitoBean private PaymentClient paymentClient;

    private TransactionTemplate transaction;
    private Long payerId;
    private Long recipientId;
    private Long productId;
    private Long rootId;
    private Long leafId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            User payer = user("payer-" + suffix);
            User recipient = user("recipient-" + suffix);
            Category root = new Category("쿠폰대분류-" + suffix, null);
            Category leaf = new Category("쿠폰소분류-" + suffix, root);
            Product product = new Product(leaf, "쿠폰상품", "테스트", null, BigDecimal.valueOf(1000), 10);
            entityManager.persist(payer);
            entityManager.persist(recipient);
            entityManager.persist(root);
            entityManager.persist(leaf);
            entityManager.persist(product);
            entityManager.persist(new Friendship(payer, recipient));
            entityManager.persist(new CreditAccount(payer.getId(), 500_000L));
            entityManager.persist(new CreditAccount(recipient.getId(), 500_000L));
            entityManager.flush();
            payerId = payer.getId();
            recipientId = recipient.getId();
            productId = product.getId();
            rootId = root.getId();
            leafId = leaf.getId();
            initialization.initialize(payerId);
            initialization.initialize(recipientId);
        });
    }

    @AfterEach
    void cleanUp() {
        reset(usages, ledger);
        jdbc.update("DELETE FROM roulette_ticket_grants WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM roulette_days WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM roulette_accounts WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM notification_outbox WHERE recipient_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM credit_transactions WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM gift_histories WHERE order_record_id IN "
                + "(SELECT id FROM orders WHERE payer_id = ?)", payerId);
        jdbc.update("DELETE FROM order_coupon_usages WHERE order_record_id IN "
                + "(SELECT id FROM orders WHERE payer_id = ?)", payerId);
        jdbc.update("DELETE FROM payment_cancellations WHERE requester_id = ?", payerId);
        jdbc.update("DELETE FROM orders WHERE payer_id = ?", payerId);
        jdbc.update("DELETE FROM coupon_inventories WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM credit_accounts WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM friendships WHERE user_id_1 IN (?, ?) OR user_id_2 IN (?, ?)",
                payerId, recipientId, payerId, recipientId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", leafId);
        jdbc.update("DELETE FROM categories WHERE id = ?", rootId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", payerId, recipientId);
    }

    @ParameterizedTest
    @CsvSource({"0,50000", "2,100000", "3,150000"})
    @DisplayName("결제 시 보상 없이 쿠폰만 확정하고 수락 때 세 금액을 한 번 지급한다")
    void accept_rewardsOnlyAfterAcceptance(int multiplier, long expectedReward) {
        Order order = completed(multiplier);
        var firstCompletion = completions.complete(order.getId());
        var repeatedCompletion = completions.complete(order.getId());
        assertThat(firstCompletion).isEqualTo(repeatedCompletion);
        assertThat(firstCompletion.balanceAfter()).isEqualTo(499_000L);
        assertThat(balance(payerId)).isEqualTo(499_000L);
        assertThat(rewardCount()).isZero();
        if (multiplier != 0) {
            assertThat(usageStatus(order)).isEqualTo("USED");
            assertThat(quantity(payerId, multiplier)).isZero();
        }
        var first = acceptances.accept(recipientId, giftId(order));
        var second = acceptances.accept(recipientId, giftId(order));
        assertThat(first).isEqualTo(second);
        assertThat(first.rewardAmount()).isEqualTo(expectedReward);
        assertThat(balance(payerId)).isEqualTo(499_000L + expectedReward);
        assertThat(balance(recipientId)).isEqualTo(500_000L);
        assertThat(rewardCount()).isEqualTo(1);
        assertRewardNotification(order, multiplier == 0 ? 1 : multiplier, expectedReward);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications "
                + "WHERE recipient_id = ? AND type = 'GIFT_COMPLETED_REWARD'", Long.class, recipientId)).isZero();
    }

    @Test
    @DisplayName("이미 지급된 과거 원장은 수락 재요청에도 금액과 잔액을 변경하지 않는다")
    void accept_preservesHistoricalReward() {
        Order order = completed(0);
        Long giftId = giftId(order);
        acceptances.accept(recipientId, giftId);
        jdbc.update("UPDATE credit_transactions SET amount = 100000, balance_after = 599000 WHERE gift_history_id = ? "
                + "AND type = 'GIFT_COMPLETED_REWARD'", giftId);
        jdbc.update("UPDATE credit_accounts SET balance = 599000 WHERE user_id = ?", payerId);
        long before = balance(payerId);
        assertThat(acceptances.accept(recipientId, giftId).rewardAmount()).isEqualTo(100_000L);
        assertThat(balance(payerId)).isEqualTo(before);
        assertThat(rewardCount()).isEqualTo(1);
        var response = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(response.expectedRewardAmount()).isNull();
        assertThat(response.rewardAmount()).isEqualTo(100_000L);
    }

    @ParameterizedTest
    @CsvSource({"0,50000", "2,100000", "3,150000"})
    @DisplayName("결제 응답과 재조회는 쿠폰·예상 보상을 제공하고 수락 후에는 원장 지급액을 제공한다")
    void contract_separatesExpectedAndPaidRewards(int multiplier, long expectedReward) throws Exception {
        if (multiplier != 0) { grant(multiplier, 1); }
        when(paymentClient.confirm(any(), any())).thenAnswer(invocation -> {
            com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest request = invocation.getArgument(0);
            return new PaymentResponse(request.orderId(), key(), key(), PaymentServerStatus.COMPLETED,
                    request.amount(), LocalDateTime.now());
        });
        String requestKey = key();
        String body = "{\"productId\":" + productId + ",\"recipientUserId\":" + recipientId
                + ",\"quantity\":1,\"expectedUnitPrice\":1000"
                + (multiplier == 0 ? "" : ",\"couponMultiplier\":" + multiplier) + "}";
        var jwt = jwt().jwt(token -> token.subject(payerId.toString()));
        mockMvc.perform(post("/payments")
                        .with(jwt).header("Idempotency-Key", requestKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath(
                        "$.data.expectedRewardAmount").value(expectedReward))
                .andExpect(jsonPath(
                        "$.data.rewardAmount").value((Object) null))
                .andExpect(jsonPath(
                        "$.data.balanceAfter").value(499_000));

        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        var waiting = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(waiting.couponMultiplier()).isEqualTo(multiplier == 0 ? null : multiplier);
        assertThat(waiting.couponStatus()).isEqualTo(multiplier == 0 ? null
                : OrderCouponUsageStatus.USED);
        assertThat(waiting.expectedRewardAmount()).isEqualTo(expectedReward);
        assertThat(waiting.rewardAmount()).isNull();
        assertThat(paymentQueries.getStatus(payerId, order.getOrderId())).isEqualTo(waiting);

        acceptances.accept(recipientId, giftId(order));
        var paid = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(paid.expectedRewardAmount()).isNull();
        assertThat(paid.rewardAmount()).isEqualTo(expectedReward);
        assertThat(completions.findCompleted(order.getId()).rewardSummary().rewardAmount())
                .isEqualTo(expectedReward);
        mockMvc.perform(post("/payments").with(jwt).header("Idempotency-Key", requestKey)
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expectedRewardAmount").value((Object) null))
                .andExpect(jsonPath("$.data.rewardAmount").value(expectedReward))
                .andExpect(jsonPath("$.data.balanceAfter").value(499_000));
        mockMvc.perform(get("/payments/{orderId}", order.getOrderId()).with(jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath(
                        "$.data.expectedRewardAmount").value((Object) null))
                .andExpect(jsonPath(
                        "$.data.rewardAmount").value(expectedReward));
        assertThat(balance(recipientId)).isEqualTo(500_000L);
    }

    @Test
    @DisplayName("미확정 예약과 실패 복구는 예상·실제 보상이 없고 타인 조회를 거부한다")
    void contract_pendingAndDeclinedDoNotPromiseRewards() throws Exception {
        grant(2, 1);
        Order order = start(key(), 2);
        var pending = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(pending.couponStatus()).isEqualTo(
                OrderCouponUsageStatus.RESERVED);
        assertThat(pending.expectedRewardAmount()).isNull();
        assertThat(pending.rewardAmount()).isNull();
        approvals.decline(order.getId());
        var declined = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(declined.couponMultiplier()).isEqualTo(2);
        assertThat(declined.couponStatus()).isEqualTo(
                OrderCouponUsageStatus.RESTORED);
        assertThat(declined.expectedRewardAmount()).isNull();
        assertThat(declined.rewardAmount()).isNull();
        mockMvc.perform(get("/payments/{orderId}", order.getOrderId())
                        .with(jwt().jwt(token -> token.subject(recipientId.toString()))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/payments/{orderId}", order.getOrderId()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("수락 원장 실패는 수락·잔액을 롤백하며 이미 사용 확정한 쿠폰은 유지한다")
    void accept_rollsBackRewardWithoutRestoringUsedCoupon() {
        Order order = completed(3);
        doThrow(new DataIntegrityViolationException("원장 저장 실패")).when(ledger).saveAndFlush(any());
        assertThatThrownBy(() -> acceptances.accept(recipientId, giftId(order)))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(giftStatus(order)).isEqualTo("WAITING_FOR_ACCEPTANCE");
        assertThat(balance(payerId)).isEqualTo(499_000L);
        assertThat(rewardCount()).isZero();
        assertThat(rewardNotificationCount()).isZero();
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 3)).isZero();
    }

    @Test
    @DisplayName("결제 원장 실패 시 사용 확정도 롤백하고 재완료 시 추가 차감하지 않는다")
    void complete_rollsBackUsedStateAndCanRetry() {
        grant(2, 1);
        Order order = start(key(), 2);
        recordApproval(order);
        doThrow(new DataIntegrityViolationException("결제 원장 실패")).when(ledger).saveAndFlush(any());
        assertThatThrownBy(() -> completions.complete(order.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(balance(payerId)).isEqualTo(500_000L);
        assertThat(quantity(payerId, 2)).isZero();
        reset(ledger);
        completions.complete(order.getId());
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);
    }

    @ParameterizedTest
    @CsvSource({"USER", "GIFT_EXPIRATION"})
    @DisplayName("미수락 취소·만료 확정 시 사용 쿠폰을 한 번 복구하고 보상은 지급하지 않는다")
    void cancellation_restoresUsedCouponOnce(CancellationSource source) {
        Order order = completed(2);
        if (source == CancellationSource.GIFT_EXPIRATION) {
            jdbc.update("UPDATE gift_histories SET expires_at = ? WHERE id = ?",
                    LocalDateTime.now().minusMinutes(1), giftId(order));
        }
        var approval = cancellation(order, source);
        assertThatThrownBy(() -> acceptances.accept(recipientId, giftId(order)))
                .isInstanceOf(GiftException.class);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        var first = compensation.compensate(approval);
        var repeated = compensation.compensate(approval);
        assertThat(first.orderId()).isEqualTo(repeated.orderId());
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        assertThat(rewardCount()).isZero();
        var restored = paymentQueries.getStatus(payerId, order.getOrderId());
        assertThat(restored.couponMultiplier()).isEqualTo(2);
        assertThat(restored.couponStatus()).isEqualTo(OrderCouponUsageStatus.RESTORED);
        assertThat(restored.expectedRewardAmount()).isNull();
        assertThat(restored.rewardAmount()).isNull();
    }

    @Test
    @DisplayName("쿠폰 복구 실패 시 취소 상태·환불·재고 복구를 함께 롤백한다")
    void cancellation_rollsBackIfCouponCannotBeRestored() {
        Order order = completed(2);
        var approval = cancellation(order, CancellationSource.USER);
        jdbc.update("UPDATE coupon_inventories SET quantity = 2147483647 WHERE user_id = ? AND multiplier = 2",
                payerId);
        assertThatThrownBy(() -> compensation.compensate(approval)).isInstanceOf(IllegalStateException.class);
        assertThat(giftStatus(order)).isEqualTo("WAITING_FOR_ACCEPTANCE");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(balance(payerId)).isEqualTo(499_000L);
    }

    @Test
    @DisplayName("환불 원장 저장 실패 시 쿠폰 복구와 선물·주문 취소도 롤백한다")
    void cancellation_rollsBackCouponRestoreIfRefundLedgerFails() {
        Order order = completed(3);
        var approval = cancellation(order, CancellationSource.USER);
        doThrow(new DataIntegrityViolationException("환불 원장 실패")).when(ledger).saveAndFlush(any());
        assertThatThrownBy(() -> compensation.compensate(approval))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 3)).isZero();
        assertThat(giftStatus(order)).isEqualTo("WAITING_FOR_ACCEPTANCE");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(balance(payerId)).isEqualTo(499_000L);
    }

    @Test
    @DisplayName("수락 완료한 선물은 외부 취소 요청 전에 거부한다")
    void cancellation_rejectsAcceptedGift() {
        Order order = completed(2);
        acceptances.accept(recipientId, giftId(order));
        assertThatThrownBy(() -> cancellation(order, CancellationSource.USER))
                .isInstanceOf(com.gift.gift.domain.order.exception.OrderException.class);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
    }

    @Test
    @DisplayName("동시 수락은 보상 한 번만 지급한다")
    void accept_concurrentlyRewardsOnce() throws Exception {
        Order order = completed(3);
        runTogether(() -> acceptances.accept(recipientId, giftId(order)),
                () -> acceptances.accept(recipientId, giftId(order)));
        assertThat(rewardCount()).isEqualTo(1);
        assertRewardNotification(order, 3, 150_000L);
        assertThat(balance(payerId)).isEqualTo(649_000L);
    }

    @Test
    @DisplayName("동시 결제 완료는 쿠폰을 추가 차감하지 않고 보상도 지급하지 않는다")
    void complete_concurrentlyConfirmsOnce() throws Exception {
        grant(2, 1);
        Order order = start(key(), 2);
        recordApproval(order);
        runTogether(() -> completions.complete(order.getId()), () -> completions.complete(order.getId()));
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);
        assertThat(rewardCount()).isZero();
    }

    @Test
    @DisplayName("수락·취소 동시 실행에서 보상 지급과 쿠폰 복구가 함께 성공하지 않는다")
    void acceptanceAndCancellation_haveOneWinner() throws Exception {
        Order order = completed(2);
        var approval = new java.util.concurrent.atomic.AtomicReference<PaymentCancellationApproval>();
        runTogether(() -> {
            try { acceptances.accept(recipientId, giftId(order)); }
            catch (GiftException expected) { }
        }, () -> {
            try { approval.set(cancellation(order, CancellationSource.USER)); }
            catch (com.gift.gift.domain.order.exception.OrderException expected) { }
        });
        if (approval.get() != null) {
            compensation.compensate(approval.get());
            assertThat(rewardCount()).isZero();
            assertThat(quantity(payerId, 2)).isEqualTo(1);
            assertThat(giftStatus(order)).isEqualTo("CANCELED");
        } else {
            assertThat(rewardCount()).isEqualTo(1);
            assertThat(quantity(payerId, 2)).isZero();
            assertThat(giftStatus(order)).isEqualTo("COMPLETED");
        }
    }

    @Test
    @DisplayName("취소 거절 확정은 수락 차단을 해제하지만 쿠폰을 복구하지 않는다")
    void cancellation_declineAllowsAcceptanceWithoutCouponRestore() {
        Order order = completed(2);
        var approval = cancellation(order, CancellationSource.USER);
        cancellationTransactions.markDeclined(approval.cancellation().getId());
        assertThat(acceptances.accept(recipientId, giftId(order)).rewardAmount()).isEqualTo(100_000L);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
    }

    @Test
    @DisplayName("외부 입력 거절 400은 주문 실패와 쿠폰 복구를 한 번만 반영한다")
    void payment_invalidRequestRestoresReservedCoupon() {
        grant(2, 1);
        String requestKey = key();
        when(paymentClient.confirm(any(), any())).thenThrow(clientFailure(PaymentClientFailure.INVALID_REQUEST));
        assertThatThrownBy(() -> payments.pay(payerId, requestKey, "APPROVE", paymentRequest(2)))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(2)))
                .isInstanceOf(OrderException.class);
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        verify(paymentClient, times(1)).confirm(any(), any());
    }

    @Test
    @DisplayName("응답 유실 후 조회에 주문이 없으면 복구하지 않고 재요청도 결과만 조회한다")
    void payment_unknownDoesNotRestoreOrSubmitAgain() {
        grant(3, 1);
        String requestKey = key();
        when(paymentClient.confirm(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.PAYMENT_RESULT_UNCONFIRMED));
        when(paymentClient.getByOrderId(any())).thenThrow(clientFailure(PaymentClientFailure.PAYMENT_NOT_FOUND));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(3)))
                    .isInstanceOfSatisfying(OrderException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_RESULT_UNCONFIRMED));
        }
        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(quantity(payerId, 3)).isZero();
        assertThat(balance(payerId)).isEqualTo(500_000L);
        verify(paymentClient, times(1)).confirm(any(), any());
        verify(paymentClient, times(2)).getByOrderId(order.getOrderId());
    }

    @Test
    @DisplayName("입력 거절과 같은 주문의 동시 요청에서 승인 명령은 한 번만 전송한다")
    void payment_concurrentInvalidRequestCannotRaceAnotherApproval() throws Exception {
        grant(2, 1);
        String requestKey = key();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            submitted.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) { throw new AssertionError("승인 요청 대기 실패"); }
            throw clientFailure(PaymentClientFailure.INVALID_REQUEST);
        }).when(paymentClient).confirm(any(), any());
        when(paymentClient.getByOrderId(any())).thenThrow(clientFailure(PaymentClientFailure.PAYMENT_NOT_FOUND));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> assertThatThrownBy(
                    () -> payments.pay(payerId, requestKey, "APPROVE", paymentRequest(2)))
                    .isInstanceOf(OrderException.class));
            assertThat(submitted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(2)))
                    .isInstanceOf(OrderException.class);
            assertThat(quantity(payerId, 2)).isZero();
            finish.countDown();
            first.get(30, TimeUnit.SECONDS);
            Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DECLINED);
            assertThat(usageStatus(order)).isEqualTo("RESTORED");
            assertThat(quantity(payerId, 2)).isEqualTo(1);
            verify(paymentClient, times(1)).confirm(any(), any());
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("정상 결제 승인과 반복 요청은 쿠폰을 한 번만 사용 확정한다")
    void payment_successfulApprovalStillUsesCouponOnce() {
        grant(2, 1);
        when(paymentClient.confirm(any(), any())).thenAnswer(invocation -> {
            com.gift.gift.infrastructure.payment.dto.PaymentConfirmRequest request = invocation.getArgument(0);
            return new PaymentResponse(request.orderId(), key(), key(), PaymentServerStatus.COMPLETED,
                    request.amount(), LocalDateTime.now());
        });
        String requestKey = key();
        var first = payments.pay(payerId, requestKey, null, paymentRequest(2));
        var repeated = payments.pay(payerId, requestKey, null, paymentRequest(2));
        assertThat(first.completion()).isEqualTo(repeated.completion());
        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);
        verify(paymentClient, times(1)).confirm(any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED"})
    @DisplayName("앞선 승인 미확정 뒤 400·401을 받아도 조회 결과가 성공이면 쿠폰을 사용 확정한다")
    void payment_retryRejectionUsesConfirmedApproval(PaymentClientFailure failure) {
        grant(2, 1);
        String requestKey = key();
        when(paymentClient.confirm(any(), any()))
                .thenThrow(clientFailure(failure).withUncertainOutcome());
        when(paymentClient.getByOrderId(any())).thenAnswer(invocation -> new PaymentResponse(
                invocation.getArgument(0), key(), key(), PaymentServerStatus.COMPLETED,
                1000L, LocalDateTime.now()
        ));

        payments.pay(payerId, requestKey, null, paymentRequest(2));

        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);
        verify(paymentClient).getByOrderId(order.getOrderId());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class,
            names = {"INVALID_REQUEST", "AUTHENTICATION_FAILED"})
    @DisplayName("재시도 거절 후 조회에도 기록이 없으면 예약을 유지하고 최종 실패 확인 때만 한 번 복구한다")
    void payment_retryRejectionWaitsForConfirmedFailure(PaymentClientFailure failure) {
        grant(3, 1);
        String requestKey = key();
        when(paymentClient.confirm(any(), any()))
                .thenThrow(clientFailure(failure).withUncertainOutcome());
        when(paymentClient.getByOrderId(any()))
                .thenThrow(clientFailure(PaymentClientFailure.PAYMENT_NOT_FOUND));

        assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(3)))
                .isInstanceOfSatisfying(OrderException.class,
                        error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_RESULT_UNCONFIRMED));
        Order pending = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(pending.getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(usageStatus(pending)).isEqualTo("RESERVED");
        assertThat(quantity(payerId, 3)).isZero();
        assertThat(balance(payerId)).isEqualTo(500_000L);

        doReturn(new PaymentResponse(
                pending.getOrderId(), key(), null, PaymentServerStatus.DECLINED, 1000L, null
        )).when(paymentClient).getByOrderId(any());
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(3)))
                    .isInstanceOf(OrderException.class);
        }
        Order declined = orders.findById(pending.getId()).orElseThrow();
        assertThat(declined.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(usageStatus(declined)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 3)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        verify(paymentClient, times(1)).confirm(any(), any());
        verify(paymentClient, times(2)).getByOrderId(pending.getOrderId());
    }

    @Test
    @DisplayName("최초 승인 인증 거절은 주문 실패와 예약 쿠폰 복구를 한 번만 반영한다")
    void payment_authenticationFailureRestoresCouponOnce() {
        grant(2, 1);
        String requestKey = key();
        when(paymentClient.confirm(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.AUTHENTICATION_FAILED));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> payments.pay(payerId, requestKey, null, paymentRequest(2)))
                    .isInstanceOf(OrderException.class);
        }
        Order order = orders.findByPayerIdAndIdempotencyKey(payerId, requestKey).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.DECLINED);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        verify(paymentClient, times(1)).confirm(any(), any());
        verify(paymentClient, never()).getByOrderId(any());
    }

    @ParameterizedTest
    @EnumSource(value = PaymentClientFailure.class, names = {"AUTHENTICATION_FAILED", "INVALID_REQUEST"})
    @DisplayName("사용자 취소 입력·인증 거절은 결제·쿠폰을 유지하고 수락 차단만 해제한다")
    void cancellation_authenticationRejectionAllowsAcceptance(PaymentClientFailure failure) {
        Order order = completed(2);
        String cancellationKey = key();
        when(paymentClient.cancel(any(), any()))
                .thenThrow(clientFailure(failure));
        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> cancellationService.cancel(
                    payerId, order.getOrderId(), cancellationKey, CancellationSource.USER
            )).isInstanceOf(OrderException.class);
        }
        assertThat(cancellationStatus(cancellationKey)).isEqualTo("DECLINED");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.COMPLETED);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);
        assertThat(acceptances.accept(recipientId, giftId(order)).rewardAmount()).isEqualTo(100_000L);
        assertThat(balance(payerId)).isEqualTo(599_000L);
        verify(paymentClient, times(1)).cancel(any(), any());
    }

    @Test
    @DisplayName("인증 거절된 사용자 취소는 새 요청 키로 다시 취소하면 쿠폰을 한 번 복구한다")
    void cancellation_newKeyCanRetryAfterAuthenticationRejection() {
        Order order = completed(2);
        when(paymentClient.cancel(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.AUTHENTICATION_FAILED));
        assertThatThrownBy(() -> cancellationService.cancel(
                payerId, order.getOrderId(), key(), CancellationSource.USER
        )).isInstanceOf(OrderException.class);
        doReturn(cancelResponse(order)).when(paymentClient).cancel(any(), any());
        String retryKey = key();
        cancellationService.cancel(payerId, order.getOrderId(), retryKey, CancellationSource.USER);
        cancellationService.cancel(payerId, order.getOrderId(), retryKey, CancellationSource.USER);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        assertThat(rewardCount()).isZero();
        verify(paymentClient, times(2)).cancel(any(), any());
    }

    @Test
    @DisplayName("취소 재시도 이후 인증 거절은 수락을 차단하고 조회로 취소 확정 후에만 복구한다")
    void cancellation_uncertainAuthenticationRejectionWaitsForFinalResult() {
        Order order = completed(3);
        String cancellationKey = key();
        when(paymentClient.cancel(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.AUTHENTICATION_FAILED).withUncertainOutcome());
        when(paymentClient.getCancellation(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.PAYMENT_NOT_FOUND));
        assertThatThrownBy(() -> cancellationService.cancel(
                payerId, order.getOrderId(), cancellationKey, CancellationSource.USER
        )).isInstanceOf(OrderException.class);
        assertThat(cancellationStatus(cancellationKey)).isEqualTo("PENDING");
        assertThatThrownBy(() -> acceptances.accept(recipientId, giftId(order)))
                .isInstanceOf(GiftException.class);
        assertThat(usageStatus(order)).isEqualTo("USED");
        assertThat(quantity(payerId, 3)).isZero();
        assertThat(balance(payerId)).isEqualTo(499_000L);

        doReturn(cancelResponse(order)).when(paymentClient).getCancellation(any(), any());
        cancellationService.cancel(payerId, order.getOrderId(), cancellationKey, CancellationSource.USER);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(quantity(payerId, 3)).isEqualTo(1);
        assertThat(balance(payerId)).isEqualTo(500_000L);
        verify(paymentClient, times(1)).cancel(any(), any());
    }

    @Test
    @DisplayName("동일 사용자 취소의 동시 요청은 인증 거절 전 다른 취소 명령을 보내지 않는다")
    void cancellation_concurrentAuthenticationRejectionSendsOnce() throws Exception {
        Order order = completed(2);
        String cancellationKey = key();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(invocation -> {
            submitted.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) { throw new AssertionError("취소 요청 대기 실패"); }
            throw clientFailure(PaymentClientFailure.AUTHENTICATION_FAILED);
        }).when(paymentClient).cancel(any(), any());
        when(paymentClient.getCancellation(any(), any()))
                .thenThrow(clientFailure(PaymentClientFailure.PAYMENT_NOT_FOUND));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var first = executor.submit(() -> assertThatThrownBy(() -> cancellationService.cancel(
                    payerId, order.getOrderId(), cancellationKey, CancellationSource.USER
            )).isInstanceOf(OrderException.class));
            assertThat(submitted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> cancellationService.cancel(
                    payerId, order.getOrderId(), cancellationKey, CancellationSource.USER
            )).isInstanceOf(OrderException.class);
            assertThatThrownBy(() -> acceptances.accept(recipientId, giftId(order)))
                    .isInstanceOf(GiftException.class);
            finish.countDown();
            first.get(30, TimeUnit.SECONDS);
            assertThat(cancellationStatus(cancellationKey)).isEqualTo("DECLINED");
            assertThat(usageStatus(order)).isEqualTo("USED");
            assertThat(quantity(payerId, 2)).isZero();
            assertThat(acceptances.accept(recipientId, giftId(order)).rewardAmount()).isEqualTo(100_000L);
            verify(paymentClient, times(1)).cancel(any(), any());
        } finally {
            finish.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("배포 전 진행 중 사용자 취소는 재전송하지 않고 만료 취소 재시도는 보존한다")
    void cancellation_migrationPreservesLegacyUncertainty() {
        Order userOrder = completed(2);
        var userRequest = cancellation(userOrder, CancellationSource.USER).cancellation();
        Order expirationOrder = completed(3);
        var expirationRequest = cancellation(expirationOrder, CancellationSource.GIFT_EXPIRATION).cancellation();
        new ResourceDatabasePopulator(new ClassPathResource(
                "db/migration/V40__preserve_pending_user_cancellation_uncertainty.sql"
        )).execute(jdbc.getDataSource());
        assertThat(cancellationTransactions.claimUserRequest(userRequest.getId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT failure_code FROM payment_cancellations WHERE id = ?",
                String.class, expirationRequest.getId())).isNull();
        assertThat(usageStatus(userOrder)).isEqualTo("USED");
        assertThat(usageStatus(expirationOrder)).isEqualTo("USED");
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(quantity(payerId, 3)).isZero();
    }

    private String cancellationStatus(String cancellationKey) {
        return jdbc.queryForObject("SELECT status FROM payment_cancellations WHERE requester_id = ? AND idempotency_key = ?",
                String.class, payerId, cancellationKey);
    }

    private com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse cancelResponse(Order order) {
        return new com.gift.gift.infrastructure.payment.dto.PaymentCancelResponse(
                order.getOrderId(), order.getPaymentKey(), key(), PaymentServerStatus.CANCELED,
                order.getAmount(), LocalDateTime.now()
        );
    }

    private PaymentCreateRequest paymentRequest(Integer multiplier) {
        return new PaymentCreateRequest(productId, recipientId, 1, 1000L, multiplier);
    }

    private PaymentClientException clientFailure(PaymentClientFailure failure) {
        return new PaymentClientException(failure, "결제 응답 테스트", (String) null);
    }

    private void runTogether(Runnable first, Runnable second) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var one = executor.submit(() -> awaitAndRun(ready, start, first));
            var two = executor.submit(() -> awaitAndRun(ready, start, second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitAndRun(CountDownLatch ready, CountDownLatch start, Runnable work) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) { throw new AssertionError("동시 시작 대기 실패"); }
            work.run();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private Order completed(int multiplier) {
        if (multiplier != 0) { grant(multiplier, 1); }
        Order order = start(key(), multiplier == 0 ? null : multiplier);
        recordApproval(order);
        completions.complete(order.getId());
        return orders.findById(order.getId()).orElseThrow();
    }

    private PaymentCancellationApproval cancellation(Order order, CancellationSource source) {
        String key = source == CancellationSource.GIFT_EXPIRATION ? "GIFT_EXPIRATION:" + giftId(order) : key();
        var started = cancellationRequests.start(payerId, order.getOrderId(), key, source);
        return new PaymentCancellationApproval(started.cancellation(), started.order(), UUID.randomUUID().toString(),
                order.getAmount(), LocalDateTime.now(), started.created());
    }

    private long balance(Long userId) {
        return jdbc.queryForObject("SELECT balance FROM credit_accounts WHERE user_id = ?", Long.class, userId);
    }

    private long rewardNotificationCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id = ? "
                + "AND type = 'GIFT_COMPLETED_REWARD'", Long.class, payerId);
    }

    private void assertRewardNotification(Order order, int multiplier, long amount) {
        assertThat(rewardNotificationCount()).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT title, message, reference_id, deduplication_key "
                + "FROM notifications WHERE recipient_id = ? AND type = 'GIFT_COMPLETED_REWARD'", payerId);
        String formattedAmount = String.format(java.util.Locale.KOREA, "%,d", amount);
        String title = multiplier > 1 ? multiplier + "배 쿠폰 보상이 지급되었어요" : "보상이 지급되었어요";
        String message = multiplier > 1
                ? "쿠폰상품 선물에 " + multiplier + "배 쿠폰이 적용되어 선물 보상 총 "
                        + formattedAmount + " 크레딧이 지급되었습니다."
                : "쿠폰상품 선물 완료 보상 " + formattedAmount + " 크레딧이 지급되었습니다.";
        assertThat(row.get("title")).isEqualTo(title);
        assertThat(row.get("message")).isEqualTo(message);
        assertThat(((Number) row.get("reference_id")).longValue()).isEqualTo(giftId(order));
        assertThat(row.get("deduplication_key"))
                .isEqualTo("GIFT_COMPLETED_REWARD:" + giftId(order) + ":" + payerId);
    }

    private long rewardCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM credit_transactions WHERE user_id = ? "
                + "AND type = 'GIFT_COMPLETED_REWARD'", Long.class, payerId);
    }

    private Long giftId(Order order) {
        return jdbc.queryForObject("SELECT id FROM gift_histories WHERE order_record_id = ?", Long.class, order.getId());
    }

    private String giftStatus(Order order) {
        return jdbc.queryForObject("SELECT status FROM gift_histories WHERE order_record_id = ?",
                String.class, order.getId());
    }

    private void recordApproval(Order order) {
        approvals.record(order.getId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), LocalDateTime.now());
    }

    private Order start(String key, Integer multiplier) {
        return requests.start(payerId, key, new PaymentCreateRequest(productId, recipientId, 1, 1000L, multiplier)).order();
    }

    private void grant(int multiplier, int count) {
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) { issue.issue(payerId, multiplier); }
        });
    }

    private int quantity(Long userId, int multiplier) {
        return jdbc.queryForObject("SELECT quantity FROM coupon_inventories WHERE user_id = ? AND multiplier = ?",
                Integer.class, userId, multiplier);
    }

    private String usageStatus(Order order) {
        return jdbc.queryForObject("SELECT status FROM order_coupon_usages WHERE order_record_id = ?",
                String.class, order.getId());
    }

    private String key() { return UUID.randomUUID().toString(); }

    private User user(String prefix) {
        return new User(prefix + "@example.com", "$2a$10$" + "a".repeat(53), "쿠폰결제", LocalDate.of(1990, 1, 1));
    }
}
