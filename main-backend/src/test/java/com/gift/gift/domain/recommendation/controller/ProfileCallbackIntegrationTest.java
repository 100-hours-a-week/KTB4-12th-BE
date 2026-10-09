package com.gift.gift.domain.recommendation.controller;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductRepository;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.entity.RecipientRecommendedProduct;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.repository.RecipientRecommendedProductRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = "app.ai-profile.service-token=test-callback-token"
)
@AutoConfigureMockMvc
@Transactional
class ProfileCallbackIntegrationTest {

    private static final String SERVICE_TOKEN = "test-callback-token";

    private static final LocalDateTime PENDING_AT =
            LocalDateTime.of(2026, 10, 5, 12, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private RecipientRecommendedProductRepository recommendationRepository;

    private Long recipientId;
    private List<Long> productIds;

    @BeforeEach
    void setUp() {
        User user = userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "수신자",
                LocalDate.of(2000, 1, 1)
        ));

        recipientId = user.getId();

        RecipientProfile profile = new RecipientProfile(user);
        profile.createNextSourceVersion();
        profile.markPending(PENDING_AT);
        profile.increaseRetryCount();

        profileRepository.saveAndFlush(profile);

        Category root = new Category(
                "대분류-" + UUID.randomUUID(),
                null
        );
        entityManager.persist(root);

        Category child = new Category(
                "세부분류-" + UUID.randomUUID(),
                root
        );
        entityManager.persist(child);

        Product first = productRepository.saveAndFlush(new Product(
                child,
                "상품1",
                "브랜드",
                null,
                BigDecimal.valueOf(10000),
                10
        ));

        Product second = productRepository.saveAndFlush(new Product(
                child,
                "상품2",
                "브랜드",
                null,
                BigDecimal.valueOf(20000),
                0
        ));

        // 상품 생성 순서와 반대로 전달하여 요청 배열 순서 보존을 검증한다.
        productIds = List.of(second.getId(), first.getId());
    }

    @Test
    @DisplayName("정상 콜백은 요청 순서대로 순위를 저장하고 프로파일을 완료한다")
    void callback_savesRanksAndCompletesProfile() throws Exception {
        send(recipientId, 1, productIds)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("추천 결과를 저장했습니다."))
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());

        entityManager.flush();
        entityManager.clear();

        List<RecipientRecommendedProduct> rows = recommendations();

        assertThat(rows)
                .extracting(row -> row.getProduct().getId())
                .containsExactlyElementsOf(productIds);

        assertThat(rows)
                .extracting(RecipientRecommendedProduct::getRankOrder)
                .containsExactly(1, 2);

        assertThat(rows)
                .extracting(RecipientRecommendedProduct::getSourceVersion)
                .containsExactly(1L, 1L);

        RecipientProfile profile = profileRepository
                .findByRecipient_Id(recipientId)
                .orElseThrow();

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("동일 버전 재전송은 추천 행을 다시 생성하지 않는다")
    void callback_isIdempotent() throws Exception {
        send(recipientId, 1, productIds)
                .andExpect(status().isOk());

        List<Long> originalIds = recommendations().stream()
                .map(RecipientRecommendedProduct::getId)
                .toList();

        send(recipientId, 1, productIds)
                .andExpect(status().isOk());

        assertThat(recommendations())
                .extracting(RecipientRecommendedProduct::getId)
                .containsExactlyElementsOf(originalIds);
    }

    @Test
    @DisplayName("수신자 불일치는 RECIPIENT_ID_MISMATCH와 traceId를 반환한다")
    void callback_rejectsRecipientMismatch() throws Exception {
        send(recipientId + 1, 1, productIds)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("RECIPIENT_ID_MISMATCH"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("없는 상품은 제외하고 입력 순서대로 연속 순위를 저장한다")
    void callback_dropsMissingProduct() throws Exception {
        send(recipientId, 1, List.of(
                productIds.getFirst(), Long.MAX_VALUE, productIds.getLast()
        )).andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());

        entityManager.flush();
        entityManager.clear();
        assertThat(recommendations())
                .extracting(row -> row.getProduct().getId())
                .containsExactlyElementsOf(productIds);
        assertThat(recommendations())
                .extracting(RecipientRecommendedProduct::getRankOrder)
                .containsExactly(1, 2);
        assertCompleted();
    }

    @Test
    @DisplayName("삭제된 상품을 제외하고 남은 상품을 순위 1로 저장한다")
    void callback_dropsDeletedProduct() throws Exception {
        entityManager.createNativeQuery("UPDATE products SET deleted_at = NOW(6) WHERE id = :id")
                .setParameter("id", productIds.getFirst()).executeUpdate();
        entityManager.clear();

        send(recipientId, 1, productIds).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(recommendations()).extracting(row -> row.getProduct().getId())
                .containsExactly(productIds.getLast());
        assertThat(recommendations()).extracting(RecipientRecommendedProduct::getRankOrder)
                .containsExactly(1);
        assertCompleted();
    }

    @Test
    @DisplayName("전부 제외된 결과는 기존 추천을 삭제하고 0행으로 완료한다")
    void callback_completesWhenAllProductsDropped() throws Exception {
        RecipientProfile profile = profileRepository.findByRecipient_Id(recipientId).orElseThrow();
        recommendationRepository.saveAllAndFlush(List.of(new RecipientRecommendedProduct(
                profile, productRepository.findById(productIds.getFirst()).orElseThrow(), 1, 1
        )));

        send(recipientId, 1, List.of(Long.MAX_VALUE)).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(recommendations()).isEmpty();
        assertCompleted();
    }

    @Test
    @DisplayName("30개 중 없는 상품 하나는 제외하고 29행의 연속 순위를 저장한다")
    void callback_saves29Of30Products() throws Exception {
        Category category = productRepository.findById(productIds.getFirst()).orElseThrow().getCategory();
        java.util.ArrayList<Long> validIds = new java.util.ArrayList<>(productIds);
        for (int index = validIds.size(); index < 29; index++) {
            validIds.add(productRepository.saveAndFlush(new Product(
                    category, "상품" + index, "브랜드", null, BigDecimal.valueOf(10000), 10
            )).getId());
        }
        java.util.Collections.reverse(validIds);
        java.util.ArrayList<Long> requested = new java.util.ArrayList<>(validIds);
        requested.add(15, Long.MAX_VALUE);

        send(recipientId, 1, requested).andExpect(status().isOk());
        entityManager.flush();
        entityManager.clear();
        assertThat(recommendations()).extracting(row -> row.getProduct().getId())
                .containsExactlyElementsOf(validIds);
        assertThat(recommendations()).extracting(RecipientRecommendedProduct::getRankOrder)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1, 29).boxed().toList());
        assertCompleted();
    }

    private void assertCompleted() {
        RecipientProfile profile = profileRepository.findByRecipient_Id(recipientId).orElseThrow();
        assertThat(profile.getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("빈 추천 결과도 정상 완료한다")
    void callback_acceptsEmptyRecommendations() throws Exception {
        send(recipientId, 1, List.of())
                .andExpect(status().isOk());

        assertThat(recommendations()).isEmpty();

        RecipientProfile profile = profileRepository
                .findByRecipient_Id(recipientId)
                .orElseThrow();

        assertThat(profile.getProfileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile.getAnalyzedSourceVersion()).isEqualTo(1);
        assertThat(profile.getPendingSince()).isNull();
        assertThat(profile.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("중복 상품 ID는 요청 검증에서 거부한다")
    void callback_rejectsDuplicateProductIds() throws Exception {
        send(
                recipientId,
                1,
                List.of(productIds.getFirst(), productIds.getFirst())
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"));

        assertThat(recommendations()).isEmpty();
    }

    @Test
    @DisplayName("미래 버전은 INVALID_REQUEST로 거부한다")
    void callback_rejectsFutureVersion() throws Exception {
        send(recipientId, 2, productIds)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"));

        assertThat(recommendations()).isEmpty();
    }

    @Test
    @DisplayName("서비스 토큰 누락은 UNAUTHORIZED와 traceId를 반환한다")
    void callback_rejectsMissingToken() throws Exception {
        mockMvc.perform(
                        post(path())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body(recipientId, 1, productIds))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code")
                        .value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private ResultActions send(
            Long bodyRecipientId,
            long version,
            List<Long> ids
    ) throws Exception {
        return mockMvc.perform(
                post(path())
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + SERVICE_TOKEN
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(bodyRecipientId, version, ids))
        );
    }

    private String path() {
        return "/api/internal/v1/recipients/"
                + recipientId
                + "/profile";
    }

    private String body(
            Long bodyRecipientId,
            long version,
            List<Long> ids
    ) {
        return objectMapper.writeValueAsString(Map.of(
                "recipientUserId", bodyRecipientId,
                "sourceVersion", version,
                "profileStatus", "COMPLETED",
                "recommendedProductIds", ids
        ));
    }

    private List<RecipientRecommendedProduct> recommendations() {
        return recommendationRepository
                .findAllByRecipientProfile_Recipient_IdAndSourceVersionOrderByRankOrderAsc(
                        recipientId,
                        1
                );
    }
}
