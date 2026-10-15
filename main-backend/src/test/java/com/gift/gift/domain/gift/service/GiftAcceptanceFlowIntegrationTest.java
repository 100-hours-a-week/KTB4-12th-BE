package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GiftAcceptanceFlowIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final String REQUEST_HASH = "a".repeat(64);
    private static final long INITIAL_BALANCE = 500_000L;
    private static final long REWARD_AMOUNT = 100_000L;

    @Autowired
    private GiftAcceptanceService giftAcceptanceService;

    @Autowired
    private GiftHistoryRepository giftHistoryRepository;

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transaction;
    private Long senderId;
    private Long recipientId;
    private Long productId;
    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long orderRecordId;
    private Long giftId;

    @BeforeEach
    void setUp() {
        transaction = new TransactionTemplate(transactionManager);

        String suffix = UUID.randomUUID().toString();

        transaction.executeWithoutResult(status -> {
            User sender = new User(
                    "sender-" + suffix + "@example.com",
                    PASSWORD_HASH,
                    "발신자",
                    LocalDate.of(1990, 1, 1)
            );
            User recipient = new User(
                    "recipient-" + suffix + "@example.com",
                    PASSWORD_HASH,
                    "수신자",
                    LocalDate.of(1990, 1, 1)
            );
            Category root = new Category(
                    "수락 대분류-" + suffix,
                    null
            );
            Category leaf = new Category(
                    "수락 소분류-" + suffix,
                    root
            );
            Product product = new Product(
                    leaf,
                    "수락 테스트 상품",
                    "테스트 브랜드",
                    null,
                    BigDecimal.valueOf(32_000L),
                    10
            );

            entityManager.persist(sender);
            entityManager.persist(recipient);
            entityManager.persist(root);
            entityManager.persist(leaf);
            entityManager.persist(product);
            entityManager.persist(new CreditAccount(
                    sender.getId(),
                    INITIAL_BALANCE
            ));
            entityManager.flush();

            Order order = orderRepository.saveAndFlush(new Order(
                    UUID.randomUUID().toString(),
                    sender.getId(),
                    recipient.getId(),
                    product.getId(),
                    2,
                    32_000L,
                    64_000L,
                    "PAYMENT-" + UUID.randomUUID(),
                    REQUEST_HASH
            ));

            GiftHistory gift = giftHistoryRepository.saveAndFlush(
                    GiftHistory.waitingForAcceptance(
                            order.getId(),
                            sender,
                            recipient,
                            product,
                            2,
                            BigDecimal.valueOf(32_000L),
                            "수락 테스트 상품",
                            UUID.randomUUID(),
                            REQUEST_HASH,
                            LocalDateTime.now().plusDays(7)
                    )
            );

            senderId = sender.getId();
            recipientId = recipient.getId();
            productId = product.getId();
            rootCategoryId = root.getId();
            leafCategoryId = leaf.getId();
            orderRecordId = order.getId();
            giftId = gift.getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update(
                "delete from credit_transactions where user_id = ?",
                senderId
        );
        jdbcTemplate.update(
                "delete from gift_histories where id = ?",
                giftId
        );
        jdbcTemplate.update(
                "delete from orders where id = ?",
                orderRecordId
        );
        jdbcTemplate.update(
                "delete from credit_accounts where user_id = ?",
                senderId
        );
        jdbcTemplate.update(
                "delete from products where id = ?",
                productId
        );
        jdbcTemplate.update(
                "delete from categories where id = ?",
                leafCategoryId
        );
        jdbcTemplate.update(
                "delete from categories where id = ?",
                rootCategoryId
        );
        jdbcTemplate.update(
                "delete from users where id in (?, ?)",
                senderId,
                recipientId
        );
    }

    @Test
    @DisplayName("선물 수락은 완료 상태와 발신자 보상을 한 번만 반영한다")
    void accept_completesGiftAndRewardsSenderOnlyOnce() {
        GiftAcceptanceResult first = giftAcceptanceService.accept(
                recipientId,
                giftId
        );
        GiftAcceptanceResult second = giftAcceptanceService.accept(
                recipientId,
                giftId
        );

        GiftHistory gift = giftHistoryRepository.findById(giftId)
                .orElseThrow();
        CreditAccount account = creditAccountRepository
                .findByUserId(senderId)
                .orElseThrow();
        CreditTransaction reward = creditTransactionRepository
                .findByDeduplicationKey(ledgerKey())
                .orElseThrow();

        assertThat(first).isEqualTo(second);
        assertThat(gift.getStatus()).isEqualTo(GiftStatus.COMPLETED);
        assertThat(gift.getCompletedAt()).isNotNull();
        assertThat(account.getBalance())
                .isEqualTo(INITIAL_BALANCE + REWARD_AMOUNT);
        assertThat(reward.getType())
                .isEqualTo(CreditTransactionType.GIFT_COMPLETED_REWARD);
        assertThat(reward.getAmount()).isEqualTo(REWARD_AMOUNT);
        assertThat(reward.getBalanceAfter())
                .isEqualTo(INITIAL_BALANCE + REWARD_AMOUNT);
        assertThat(reward.getGiftHistoryId()).isEqualTo(giftId);
        assertThat(reward.getOrderRecordId()).isEqualTo(orderRecordId);
    }

    @Test
    @DisplayName("보상 원장 저장 실패 시 선물 완료와 크레딧 지급을 모두 롤백한다")
    void accept_rollsBackGiftAndBalance_whenRewardLedgerFails() {
        transaction.executeWithoutResult(status ->
                creditTransactionRepository.saveAndFlush(
                        new CreditTransaction(
                                senderId,
                                CreditTransactionType.GIFT_COMPLETED_REWARD,
                                REWARD_AMOUNT,
                                INITIAL_BALANCE + REWARD_AMOUNT,
                                orderRecordId,
                                giftId,
                                null,
                                ledgerKey()
                        )
                )
        );

        assertThatThrownBy(() -> giftAcceptanceService.accept(
                recipientId,
                giftId
        )).isInstanceOf(DataIntegrityViolationException.class);

        GiftHistory gift = giftHistoryRepository.findById(giftId)
                .orElseThrow();
        CreditAccount account = creditAccountRepository
                .findByUserId(senderId)
                .orElseThrow();

        assertThat(gift.getStatus())
                .isEqualTo(GiftStatus.WAITING_FOR_ACCEPTANCE);
        assertThat(gift.getCompletedAt()).isNull();
        assertThat(account.getBalance()).isEqualTo(INITIAL_BALANCE);
    }

    private String ledgerKey() {
        return "GIFT_COMPLETED_REWARD:" + giftId + ":" + senderId;
    }
}
