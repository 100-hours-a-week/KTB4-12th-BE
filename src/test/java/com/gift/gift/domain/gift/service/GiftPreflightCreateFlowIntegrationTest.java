package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class GiftPreflightCreateFlowIntegrationTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final int INITIAL_QUANTITY = 5;
    private static final BigDecimal UNIT_PRICE = BigDecimal.valueOf(32_000);

    @Autowired
    private GiftService giftService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long rootCategoryId;
    private Long leafCategoryId;
    private Long productId;
    private Long senderId;
    private Long recipientId;

    @BeforeEach
    void setUp() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        String suffix = UUID.randomUUID().toString();

        transaction.executeWithoutResult(status -> {
            Category rootCategory = new Category("Preflight-Create 흐름 대분류-" + suffix, null);
            Category leafCategory = new Category("Preflight-Create 흐름 소분류-" + suffix, rootCategory);
            User sender = new User(
                    "flow-sender-" + suffix + "@example.com", PASSWORD_HASH, "발신자", LocalDate.of(1990, 1, 1));
            User recipient = new User(
                    "flow-recipient-" + suffix + "@example.com", PASSWORD_HASH, "수신자", LocalDate.of(1990, 1, 1));
            Product product = new Product(
                    leafCategory, "Preflight-Create 흐름 상품", "테스트 브랜드", null, UNIT_PRICE, INITIAL_QUANTITY);

            entityManager.persist(rootCategory);
            entityManager.persist(leafCategory);
            entityManager.persist(sender);
            entityManager.persist(recipient);
            entityManager.persist(product);
            entityManager.persist(new Friend(sender, recipient));
            entityManager.flush();

            rootCategoryId = rootCategory.getId();
            leafCategoryId = leafCategory.getId();
            productId = product.getId();
            senderId = sender.getId();
            recipientId = recipient.getId();
        });
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM gift_histories WHERE sender_id = ?", senderId);
        jdbcTemplate.update("DELETE FROM friends WHERE user_id = ?", senderId);
        jdbcTemplate.update("DELETE FROM products WHERE id = ?", productId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", leafCategoryId);
        jdbcTemplate.update("DELETE FROM categories WHERE id = ?", rootCategoryId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", senderId);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", recipientId);
    }

    @Test
    @DisplayName("사전 검증 응답의 단가와 수량으로 생성을 요청하면 선물이 정상 생성되고 재고·판매량이 반영된다")
    void createGift_succeeds_withPreflightResponseValues() {
        int quantity = 2;
        GiftPreflightResponse preflightResponse = giftService.preflight(
                senderId, new GiftPreflightRequest(productId, recipientId, quantity));

        assertThat(preflightResponse.product().maxOrderQuantity()).isEqualTo(INITIAL_QUANTITY);
        assertThat(preflightResponse.product().unitPrice()).isEqualByComparingTo(UNIT_PRICE);

        GiftCreateRequest createRequest = new GiftCreateRequest(
                productId, recipientId, quantity, preflightResponse.product().unitPrice());

        GiftHistory giftHistory = giftService.createGift(senderId, UUID.randomUUID(), createRequest);

        assertThat(giftHistory.getId()).isNotNull();
        assertThat(giftHistory.getQuantity()).isEqualTo(quantity);
        assertThat(giftHistory.getProductPriceSnapshot()).isEqualByComparingTo(UNIT_PRICE);

        Integer remainingQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM products WHERE id = ?", Integer.class, productId);
        assertThat(remainingQuantity).isEqualTo(INITIAL_QUANTITY - quantity);

        Integer sales = jdbcTemplate.queryForObject(
                "SELECT sales FROM products WHERE id = ?", Integer.class, productId);
        assertThat(sales).isEqualTo(quantity);
    }

    @Test
    @DisplayName("사전 검증 이후 가격이 변경되면 생성 요청은 GIFT_CONDITIONS_CHANGED로 거부된다")
    void createGift_throwsGiftConditionsChanged_whenPriceChangedAfterPreflight() {
        int quantity = 1;
        GiftPreflightResponse preflightResponse = giftService.preflight(
                senderId, new GiftPreflightRequest(productId, recipientId, quantity));

        jdbcTemplate.update(
                "UPDATE products SET price = ? WHERE id = ?", UNIT_PRICE.add(BigDecimal.valueOf(1_000)), productId);

        GiftCreateRequest createRequest = new GiftCreateRequest(
                productId, recipientId, quantity, preflightResponse.product().unitPrice());

        assertThatThrownBy(() -> giftService.createGift(senderId, UUID.randomUUID(), createRequest))
                .isInstanceOf(GiftException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.GIFT_CONDITIONS_CHANGED);

        Integer remainingQuantity = jdbcTemplate.queryForObject(
                "SELECT quantity FROM products WHERE id = ?", Integer.class, productId);
        assertThat(remainingQuantity).isEqualTo(INITIAL_QUANTITY);
    }
}
