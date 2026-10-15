package com.gift.gift.domain.friend.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.credit.service.SignupCreditService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FriendGiftFlowIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SignupCreditService signupCreditService;
    @Autowired private JdbcTemplate jdbc;

    private User requester;
    private User receiver;
    private Long productId;
    private Long rootId;
    private Long categoryId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            requester = new User("requester-" + suffix + "@example.com", "$2a$10$" + "a".repeat(53),
                    "요청자", LocalDate.of(1990, 1, 1));
            receiver = new User("receiver-" + suffix + "@example.com", "$2a$10$" + "a".repeat(53),
                    "수신자", LocalDate.of(1990, 2, 2));
            Category root = new Category("흐름 대분류-" + suffix, null);
            Category category = new Category("흐름 소분류-" + suffix, root);
            Product product = new Product(category, "흐름 상품", "브랜드", null, BigDecimal.valueOf(32000), 5);
            entityManager.persist(requester);
            entityManager.persist(receiver);
            entityManager.persist(root);
            entityManager.persist(category);
            entityManager.persist(product);
            entityManager.flush();
            signupCreditService.grant(requester.getId());
            signupCreditService.grant(receiver.getId());
            rootId = root.getId();
            categoryId = category.getId();
            productId = product.getId();
        });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("사용자 검색부터 수락·선물·친구 삭제까지 양방향 정책과 기존 선물 이력을 유지한다")
    void friendshipFlow_controlsGiftingInBothDirections(boolean reverseGift) throws Exception {
        mvc.perform(get("/users/search").param("email", receiver.getEmail())
                        .with(jwt().jwt(j -> j.subject(requester.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.userId").value(receiver.getId()));
        MvcResult created = mvc.perform(post("/friend-requests")
                        .with(jwt().jwt(j -> j.subject(requester.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receiverId\":" + receiver.getId() + "}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn();
        long requestId = mapper.readTree(created.getResponse().getContentAsString()).path("data")
                .path("requestId").asLong();
        mvc.perform(get("/friend-requests/received")
                        .with(jwt().jwt(j -> j.subject(receiver.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].requestId").value(requestId));

        User sender = reverseGift ? receiver : requester;
        User recipient = reverseGift ? requester : receiver;
        friends(requester, receiver, 0);
        friends(receiver, requester, 0);
        preflight(sender, recipient, false);
        mvc.perform(post("/friend-requests/{id}/accept", requestId)
                        .with(jwt().jwt(j -> j.subject(receiver.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        for (User owner : new User[] {requester, receiver}) {
            mvc.perform(get("/friend-requests/" + (owner == receiver ? "received" : "sent"))
                            .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        }
        long friendId = friends(requester, receiver, 1);
        assertThat(friends(receiver, requester, 1)).isEqualTo(friendId);
        preflight(sender, recipient, true);
        String giftBody = giftBody(recipient, true);
        MvcResult gift = mvc.perform(post("/gifts").header("Idempotency-Key", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(sender.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(giftBody))
                .andExpect(status().isCreated()).andReturn();
        long giftId = mapper.readTree(gift.getResponse().getContentAsString()).path("data")
                .path("gift").path("giftId").asLong();

        // Preflight는 친구 관계를 예약하지 않으므로 삭제 후 Create가 다시 검증해야 한다.
        preflight(sender, recipient, true);
        mvc.perform(delete("/friends/{id}", friendId)
                        .with(jwt().jwt(j -> j.subject(recipient.getId().toString()))))
                .andExpect(status().isNoContent());
        friends(requester, receiver, 0);
        friends(receiver, requester, 0);
        preflight(sender, recipient, false);
        mvc.perform(post("/gifts").header("Idempotency-Key", UUID.randomUUID())
                        .with(jwt().jwt(j -> j.subject(sender.getId().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(giftBody))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("RECIPIENT_NOT_FRIEND"));
        mvc.perform(get("/gifts/sent/{id}", giftId)
                        .with(jwt().jwt(j -> j.subject(sender.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.giftId").value(giftId));
        mvc.perform(get("/gifts/received/{id}", giftId)
                        .with(jwt().jwt(j -> j.subject(recipient.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.giftId").value(giftId));
        assertThat(jdbc.queryForObject("SELECT quantity FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sales FROM products WHERE id = ?", Integer.class, productId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM gift_histories WHERE product_id = ?",
                Long.class, productId)).isEqualTo(1);
    }

    private long friends(User owner, User other, int count) throws Exception {
        MvcResult result = mvc.perform(get("/friends")
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.friends.length()").value(count))
                .andReturn();
        ResultActions search = mvc.perform(get("/friends/search").param("query", other.getName())
                        .with(jwt().jwt(j -> j.subject(owner.getId().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.friends.length()").value(count));
        if (count == 0) {
            return 0;
        }
        search.andExpect(jsonPath("$.data.friends[0].userId").value(other.getId()));
        JsonNode friend = mapper.readTree(result.getResponse().getContentAsString()).path("data").path("friends").get(0);
        assertThat(friend.path("userId").asLong()).isEqualTo(other.getId());
        assertThat(friend.path("name").asText()).isEqualTo(other.getName());
        assertThat(friend.path("email").asText()).isEqualTo(other.getEmail());
        assertThat(friend.path("birth").isNull()).isTrue();
        return friend.path("friendId").asLong();
    }

    private void preflight(User sender, User recipient, boolean allowed) throws Exception {
        ResultActions result = mvc.perform(post("/gifts/preflight")
                .with(jwt().jwt(j -> j.subject(sender.getId().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(giftBody(recipient, false)));
        if (allowed) {
            result.andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.recipient.userId").value(recipient.getId()))
                    .andExpect(jsonPath("$.data.product.unitPrice").value(32000));
        } else {
            result.andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.error.code").value("RECIPIENT_NOT_FRIEND"));
        }
    }

    private String giftBody(User recipient, boolean create) {
        return "{\"productId\":" + productId + ",\"recipientUserId\":" + recipient.getId()
                + ",\"quantity\":1" + (create ? ",\"expectedUnitPrice\":32000" : "") + "}";
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notifications WHERE recipient_id IN (?, ?)", requester.getId(), receiver.getId());
        jdbc.update("DELETE FROM credit_transactions WHERE user_id IN (?, ?)", requester.getId(), receiver.getId());
        jdbc.update("DELETE FROM gift_histories WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM friend_requests WHERE requester_id IN (?, ?)", requester.getId(), receiver.getId());
        jdbc.update("DELETE FROM friendships WHERE user_id_1 IN (?, ?)", requester.getId(), receiver.getId());
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
        jdbc.update("DELETE FROM categories WHERE id = ?", categoryId);
        jdbc.update("DELETE FROM categories WHERE id = ?", rootId);
        jdbc.update("DELETE FROM credit_accounts WHERE user_id IN (?, ?)", requester.getId(), receiver.getId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", requester.getId(), receiver.getId());
    }
}
