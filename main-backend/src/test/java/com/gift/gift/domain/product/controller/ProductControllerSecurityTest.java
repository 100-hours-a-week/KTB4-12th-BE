package com.gift.gift.domain.product.controller;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.friend.repository.FriendshipRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProductControllerSecurityTest {

    private static String passwordHash;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private FriendshipRepository friendRepository;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(4).encode("Password1!");
    }

    @Test
    @DisplayName("일반 상품 조회는 로그인 없이 인기순으로 조회한다")
    void getProducts_allowsAnonymousGeneralSearch() throws Exception {
        mockMvc.perform(get("/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort").value("POPULAR"))
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    @DisplayName("수신자 없는 AI 추천순 요청은 400 공통 오류를 반환한다")
    void getProducts_rejectsAiRecommendedWithoutRecipient() throws Exception {
        expectError(mockMvc.perform(get("/products").param("sort", "AI_RECOMMENDED"))
                .andExpect(status().isBadRequest()), "INVALID_REQUEST",
                "조회 조건을 확인해 주세요.");
    }

    @Test
    @DisplayName("수신자 기본 정렬 조회는 비로그인일 때 401을 반환한다")
    void getProducts_requiresLoginForRecipientDefaultSort() throws Exception {
        expectError(mockMvc.perform(get("/products").param("recipientUserId", "10"))
                .andExpect(status().isUnauthorized()), "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    @Test
    @DisplayName("수신자 인기순 조회도 비로그인일 때 401을 반환한다")
    void getProducts_requiresLoginForRecipientPopularSort() throws Exception {
        expectError(mockMvc.perform(get("/products")
                        .param("recipientUserId", "10").param("sort", "POPULAR"))
                .andExpect(status().isUnauthorized()), "UNAUTHORIZED", "로그인이 필요합니다.");
    }

    @Test
    @DisplayName("등록한 활성 친구는 정렬 생략과 AI 정렬에서 추천 결과가 없으면 인기순 fallback으로 조회한다")
    void getProducts_allowsRegisteredFriend() throws Exception {
        User owner = persistedUser();
        User recipient = persistedUser();
        friendRepository.saveAndFlush(new Friendship(owner, recipient));

        mockMvc.perform(get("/products")
                        .param("recipientUserId", recipient.getId().toString())
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort").value("POPULAR"));

        mockMvc.perform(get("/products")
                        .param("recipientUserId", recipient.getId().toString())
                        .param("sort", "AI_RECOMMENDED")
                        .with(jwt().jwt(token -> token.subject(owner.getId().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appliedSort").value("POPULAR"));
    }

    @Test
    @DisplayName("등록하지 않은 수신자는 명시적 인기순에서도 404를 반환한다")
    void getProducts_rejectsNonFriend() throws Exception {
        User owner = persistedUser();
        User recipient = persistedUser();

        expectRecipientNotFound(mockMvc.perform(get("/products")
                .param("recipientUserId", recipient.getId().toString())
                .param("sort", "POPULAR")
                .with(jwt().jwt(token -> token.subject(owner.getId().toString())))));
    }

    @Test
    @DisplayName("친구 관계의 생성 방향과 무관하게 수신자 조회를 허용한다")
    void getProducts_allowsEitherSideOfFriendship() throws Exception {
        User owner = persistedUser();
        User recipient = persistedUser();
        friendRepository.saveAndFlush(new Friendship(recipient, owner));

        recipientSearch(owner, recipient).andExpect(status().isOk());
    }

    @Test
    @DisplayName("삭제된 친구 관계로는 수신자 조회를 할 수 없다")
    void getProducts_rejectsDeletedFriendship() throws Exception {
        User owner = persistedUser();
        User recipient = persistedUser();
        Friendship friendship = new Friendship(owner, recipient);
        ReflectionTestUtils.setField(friendship, "deletedAt", LocalDateTime.now());
        friendRepository.saveAndFlush(friendship);

        expectRecipientNotFound(recipientSearch(owner, recipient));
    }

    @ParameterizedTest
    @ValueSource(strings = {"deletedAt", "status"})
    @DisplayName("삭제되거나 비활성인 수신자는 친구로 등록되어 있어도 조회할 수 없다")
    void getProducts_rejectsUnavailableRecipient(String field) throws Exception {
        User owner = persistedUser();
        User recipient = persistedUser();
        friendRepository.saveAndFlush(new Friendship(owner, recipient));
        ReflectionTestUtils.setField(recipient, field,
                field.equals("deletedAt") ? LocalDateTime.now() : UserStatus.DELETED);
        userRepository.saveAndFlush(recipient);

        expectRecipientNotFound(recipientSearch(owner, recipient));
    }

    private ResultActions recipientSearch(User owner, User recipient) throws Exception {
        return mockMvc.perform(get("/products")
                .param("recipientUserId", recipient.getId().toString())
                .with(jwt().jwt(token -> token.subject(owner.getId().toString()))));
    }

    private void expectRecipientNotFound(ResultActions result) throws Exception {
        expectError(result.andExpect(status().isNotFound()), "RECIPIENT_NOT_FOUND",
                "선택한 수신자 정보를 확인할 수 없습니다.");
    }

    private void expectError(ResultActions result, String code, String message) throws Exception {
        result.andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.error.code").value(code))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.error.details").doesNotExist())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private User persistedUser() {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                passwordHash,
                "상품조회사용자",
                LocalDate.of(2000, 1, 1)
        ));
    }
}
