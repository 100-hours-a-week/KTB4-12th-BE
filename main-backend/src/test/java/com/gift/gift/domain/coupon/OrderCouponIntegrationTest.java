package com.gift.gift.domain.coupon;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.coupon.entity.OrderCouponUsage;
import com.gift.gift.domain.coupon.repository.OrderCouponUsageRepository;
import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.coupon.service.CouponIssueService;
import com.gift.gift.domain.coupon.service.OrderCouponService;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.order.entity.CancellationSource;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.entity.OrderFailureCode;
import com.gift.gift.domain.order.entity.OrderStatus;
import com.gift.gift.domain.order.entity.PaymentCancellation;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.order.service.OrderApprovalTransactionService;
import com.gift.gift.domain.order.service.PaymentAutoCancellationTransactionService;
import com.gift.gift.domain.order.service.PaymentCancellationApproval;
import com.gift.gift.domain.order.service.PaymentRequestService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@SpringBootTest(properties = "app.coupon.payment-enabled=true")
class OrderCouponIntegrationTest {

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
        reset(usages);
        jdbc.update("DELETE FROM order_coupon_usages WHERE order_record_id IN "
                + "(SELECT id FROM orders WHERE payer_id = ?)", payerId);
        jdbc.update("DELETE FROM payment_cancellations WHERE requester_id = ?", payerId);
        jdbc.update("DELETE FROM orders WHERE payer_id = ?", payerId);
        jdbc.update("DELETE FROM coupon_inventories WHERE user_id IN (?, ?)", payerId, recipientId);
        jdbc.update("DELETE FROM credit_accounts WHERE user_id = ?", payerId);
        jdbc.update("DELETE FROM friendships WHERE user_id_1 IN (?, ?) OR user_id_2 IN (?, ?)",
                payerId, recipientId, payerId, recipientId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", leafId);
        jdbc.update("DELETE FROM categories WHERE id = ?", rootId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", payerId, recipientId);
    }

    @Test
    @DisplayName("주문 생성 시 수량 한 개와 예약을 연결하고 같은 요청은 추가 차감하지 않는다")
    void start_reservesOnceForSameIdempotencyKey() {
        grant(2, 2);
        String key = key();
        Order first = start(key, 2);
        Order repeated = start(key, 2);
        transaction.executeWithoutResult(status -> coupons.reserve(first.getId(), 2));
        assertThat(repeated.getId()).isEqualTo(first.getId());
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(usageStatus(first)).isEqualTo("RESERVED");
        assertThat(orderCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM order_coupon_usages WHERE order_record_id = ?",
                Long.class, first.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 멱등 키로 다른 배수나 미사용으로 변경하면 충돌하고 추가 차감하지 않는다")
    void start_rejectsChangedCouponSelection() {
        grant(2, 1);
        grant(3, 1);
        String key = key();
        start(key, 2);
        for (Integer multiplier : new Integer[]{3, null}) {
            assertThatThrownBy(() -> start(key, multiplier))
                    .isInstanceOfSatisfying(BusinessException.class,
                            failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
        }
        assertThat(quantity(payerId, 3)).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("쿠폰 미사용 주문에는 예약 기록과 차감이 없다")
    void start_withoutCouponPreservesExistingFlow() {
        grant(2, 1);
        Order order = start(key(), null);
        assertThat(usages.findByOrderRecordId(order.getId())).isEmpty();
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        approvals.decline(order.getId());
        assertThat(quantity(payerId, 2)).isEqualTo(1);
    }

    @Test
    @DisplayName("본인 수량이 없으면 타인의 수량을 사용하지 않고 주문도 롤백한다")
    void start_doesNotConsumeAnotherUsersInventory() {
        transaction.executeWithoutResult(status -> issue.issue(recipientId, 2));
        assertThatThrownBy(() -> start(key(), 2))
                .isInstanceOfSatisfying(BusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.COUPON_NOT_AVAILABLE));
        assertThat(quantity(recipientId, 2)).isEqualTo(1);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("초기 행이 없으면 주문 생성도 실패한다")
    void start_rejectsMissingInventory() {
        jdbc.update("DELETE FROM coupon_inventories WHERE user_id = ? AND multiplier = 2", payerId);
        assertThatThrownBy(() -> start(key(), 2)).isInstanceOf(IllegalStateException.class);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("예약 기록 저장 실패 시 수량 차감과 주문 생성을 롤백한다")
    void start_rollsBackWhenUsageSaveFails() {
        grant(2, 1);
        doThrow(new DataIntegrityViolationException("예약 기록 실패"))
                .when(usages).saveAndFlush(any(OrderCouponUsage.class));
        assertThatThrownBy(() -> start(key(), 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(orderCount()).isZero();
    }

    @Test
    @DisplayName("수량 한 개에 동시 주문이 오면 한 건만 예약한다")
    void start_concurrentlyReservesOnlyAvailableQuantity() throws Exception {
        grant(2, 1);
        List<ErrorCode> failures = race(key(), key());
        assertThat(failures).containsExactly(ErrorCode.COUPON_NOT_AVAILABLE);
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("수량 두 개이면 동시 주문 두 건을 각각 예약할 수 있다")
    void start_concurrentlyReservesTwoAvailableCoupons() throws Exception {
        grant(2, 2);
        assertThat(race(key(), key())).isEmpty();
        assertThat(quantity(payerId, 2)).isZero();
        assertThat(orderCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("동일 멱등 키의 동시 요청도 주문 한 건과 예약 한 건만 생성한다")
    void start_concurrentIdenticalRequestsDoNotDoubleReserve() throws Exception {
        grant(2, 2);
        String key = key();
        assertThat(race(key, key)).isEmpty();
        assertThat(orderCount()).isEqualTo(1);
        assertThat(quantity(payerId, 2)).isEqualTo(1);
    }

    @Test
    @DisplayName("거절 확정은 한 번만 복구하고 재요청에도 수량을 추가하지 않는다")
    void decline_restoresExactlyOnce() {
        grant(2, 1);
        Order order = start(key(), 2);
        approvals.decline(order.getId());
        approvals.decline(order.getId());
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.DECLINED);
    }

    @Test
    @DisplayName("결과 미확정이나 운영 확인 대상은 예약을 유지한다")
    void restore_rejectsPendingAndUnknownResults() {
        grant(2, 1);
        Order order = start(key(), 2);
        assertCannotRestore(order);
        approvals.markUnknown(order.getId(), OrderFailureCode.PAYMENT_RESULT_UNCONFIRMED);
        assertCannotRestore(order);
        jdbc.update("UPDATE orders SET failure_code = 'PAYMENT_RECONCILIATION_REQUIRED' WHERE id = ?", order.getId());
        assertCannotRestore(order);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(quantity(payerId, 2)).isZero();
    }

    @Test
    @DisplayName("쿠폰 복구 실패 시 결제 거절과 예약 상태 변경을 함께 롤백한다")
    void decline_rollsBackWhenQuantityCannotBeRestored() {
        grant(2, 1);
        Order order = start(key(), 2);
        jdbc.update("UPDATE coupon_inventories SET quantity = 2147483647 WHERE user_id = ? AND multiplier = 2", payerId);
        assertThatThrownBy(() -> approvals.decline(order.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(quantity(payerId, 2)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("승인된 주문을 늦게 온 거절로 덮어쓰거나 쿠폰을 복구하지 않는다")
    void decline_doesNotOverwriteApproval() {
        grant(2, 1);
        Order order = start(key(), 2);
        recordApproval(order);
        assertThatThrownBy(() -> approvals.decline(order.getId())).isInstanceOf(IllegalStateException.class);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(quantity(payerId, 2)).isZero();
    }

    @Test
    @DisplayName("자동 취소 확정과 반복 처리에서 쿠폰은 한 번만 복구한다")
    void automaticCancellation_restoresOnceAfterConfirmation() {
        grant(2, 1);
        Order order = start(key(), 2);
        PaymentCancellationApproval approval = automaticApproval(order);
        assertCannotRestore(order);
        autoCancellation.complete(approval);
        autoCancellation.complete(approval);
        assertThat(quantity(payerId, 2)).isEqualTo(1);
        assertThat(usageStatus(order)).isEqualTo("RESTORED");
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELED);
    }

    @Test
    @DisplayName("자동 취소 확정 중 쿠폰 복구 실패 시 주문·취소 원장도 롤백한다")
    void automaticCancellation_rollsBackIfRestorationFails() {
        grant(2, 1);
        Order order = start(key(), 2);
        PaymentCancellationApproval approval = automaticApproval(order);
        jdbc.update("UPDATE coupon_inventories SET quantity = 2147483647 WHERE user_id = ? AND multiplier = 2", payerId);
        assertThatThrownBy(() -> autoCancellation.complete(approval)).isInstanceOf(IllegalStateException.class);
        assertThat(orders.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PENDING);
        assertThat(usageStatus(order)).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("SELECT status FROM payment_cancellations WHERE id = ?",
                String.class, approval.cancellation().getId())).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("예약·복구는 외부 트랜잭션 없이 호출할 수 없다")
    void couponOperations_requireCallerTransaction() {
        assertThatThrownBy(() -> coupons.reserve(1L, 2)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> coupons.restoreAfterFailure(1L)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("같은 주문을 다른 배수로 재예약하면 충돌한다")
    void reserve_rejectsChangingMultiplier() {
        grant(2, 1);
        grant(3, 1);
        Order order = start(key(), 2);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> coupons.reserve(order.getId(), 3)))
                .isInstanceOf(BusinessException.class);
        assertThat(quantity(payerId, 3)).isEqualTo(1);
    }

    private void assertCannotRestore(Order order) {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> coupons.restoreAfterFailure(order.getId())))
                .isInstanceOf(IllegalStateException.class);
    }

    private PaymentCancellationApproval automaticApproval(Order order) {
        recordApproval(order);
        PaymentCancellation cancellation = transaction.execute(status -> {
            PaymentCancellation saved = new PaymentCancellation(order.getId(), payerId,
                    CancellationSource.LOCAL_PROCESSING_FAILURE, key(), "a".repeat(64));
            entityManager.persist(saved);
            entityManager.flush();
            return saved;
        });
        return new PaymentCancellationApproval(cancellation, order, UUID.randomUUID().toString(),
                order.getAmount(), LocalDateTime.now(), true);
    }

    private void recordApproval(Order order) {
        approvals.record(order.getId(), UUID.randomUUID().toString(), UUID.randomUUID().toString(), LocalDateTime.now());
    }

    private Order start(String key, Integer multiplier) {
        return requests.start(payerId, key, new PaymentCreateRequest(productId, recipientId, 1, 1000L, multiplier)).order();
    }

    private void grant(int multiplier, int count) {
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < count; i++) {
                issue.issue(payerId, multiplier);
            }
        });
    }

    private int quantity(Long userId, int multiplier) {
        return jdbc.queryForObject("SELECT quantity FROM coupon_inventories WHERE user_id = ? AND multiplier = ?",
                Integer.class, userId, multiplier);
    }

    private long orderCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE payer_id = ?", Long.class, payerId);
    }

    private String usageStatus(Order order) {
        return jdbc.queryForObject("SELECT status FROM order_coupon_usages WHERE order_record_id = ?",
                String.class, order.getId());
    }

    private List<ErrorCode> race(String firstKey, String secondKey) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<java.util.concurrent.Future<ErrorCode>> results = new ArrayList<>();
            for (String key : List.of(firstKey, secondKey)) {
                Callable<ErrorCode> action = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new AssertionError("동시 시작 대기 시간 초과");
                    }
                    try {
                        start(key, 2);
                        return null;
                    } catch (BusinessException failure) {
                        return failure.getErrorCode();
                    }
                };
                results.add(executor.submit(action));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ErrorCode> failures = new ArrayList<>();
            for (var result : results) {
                ErrorCode failure = result.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private User user(String prefix) {
        return new User(prefix + "@example.com", "$2a$10$" + "a".repeat(53), "쿠폰결제", LocalDate.of(1990, 1, 1));
    }
}
