package com.gift.gift.domain.recommendation.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = "app.ai-profile.service-token=test-callback-token"
)
@AutoConfigureMockMvc
class ProfileCallbackTransactionIntegrationTest {

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private ProfileCallbackService callbackService;

    @MockitoSpyBean
    private RecipientRecommendedProductRepository recommendationRepository;

    private TransactionTemplate transactions;

    private Long recipientId;
    private Long rootCategoryId;
    private Long childCategoryId;

    private List<Long> productIds;
    private Snapshot beforeCallback;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(status -> {
            User user = userRepository.saveAndFlush(new User(
                    UUID.randomUUID() + "@example.com",
                    "$2a$10$" + "a".repeat(53),
                    "콜백 테스트 수신자",
                    LocalDate.of(2000, 1, 1)
            ));

            recipientId = user.getId();

            RecipientProfile profile = new RecipientProfile(user);
            profile.createNextSourceVersion();
            profile.markPending(PENDING_AT);

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

            Product first = createProduct(child, "상품1", 10000);
            Product second = createProduct(child, "상품2", 20000);
            Product third = createProduct(child, "상품3", 30000);

            entityManager.flush();

            rootCategoryId = root.getId();
            childCategoryId = child.getId();

            productIds = List.of(
                    first.getId(),
                    second.getId(),
                    third.getId()
            );
        });

        // 버전 1의 기존 추천 결과를 실제로 커밋한다.
        callbackService.saveCallback(
                recipientId,
                recipientId,
                1,
                List.of(productIds.get(0), productIds.get(1))
        );

        // 버전 2 콜백을 기다리는 상태도 별도 트랜잭션으로 커밋한다.
        transactions.executeWithoutResult(status -> {
            RecipientProfile profile = profileRepository
                    .findByRecipientIdForUpdate(recipientId)
                    .orElseThrow();

            profile.createNextSourceVersion();
            profile.markPending(PENDING_AT);
            profile.recordPreferenceChange(PENDING_AT);
            profile.restartDebounce(PENDING_AT);
            profile.increaseRetryCount();
        });

        beforeCallback = snapshot();

        assertThat(beforeCallback.profileStatus())
                .isEqualTo(RecipientProfileStatus.PENDING);
        assertThat(beforeCallback.sourceVersion()).isEqualTo(2);
        assertThat(beforeCallback.analyzedSourceVersion()).isEqualTo(1);
        assertThat(beforeCallback.retryCount()).isEqualTo(1);
        assertThat(beforeCallback.recommendations()).hasSize(2);
    }

    @AfterEach
    void tearDown() {
        // 실패를 주입한 설정이 정리 작업에 영향을 주지 않게 한다.
        reset(recommendationRepository);

        if (transactions == null || recipientId == null) {
            return;
        }

        transactions.executeWithoutResult(status -> {
            recommendationRepository.deleteAllByRecipientId(recipientId);

            profileRepository.findByRecipient_Id(recipientId)
                    .ifPresent(profileRepository::delete);

            profileRepository.flush();

            if (productIds != null) {
                productRepository.deleteAllByIdInBatch(productIds);
            }

            if (childCategoryId != null) {
                entityManager.remove(
                        entityManager.getReference(
                                Category.class,
                                childCategoryId
                        )
                );
                entityManager.flush();
            }

            if (rootCategoryId != null) {
                entityManager.remove(
                        entityManager.getReference(
                                Category.class,
                                rootCategoryId
                        )
                );
                entityManager.flush();
            }

            userRepository.deleteById(recipientId);
            userRepository.flush();
        });
    }

    @Test
    @DisplayName("정상 콜백은 추천 교체와 프로파일 완료를 실제 커밋한다")
    void callback_commitsRecommendationsAndProfileTogether()
            throws Exception {

        List<Long> requestedIds = replacementProductIds();

        send(2, requestedIds)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("추천 결과를 저장했습니다."))
                .andExpect(jsonPath("$.data").isEmpty());

        // HTTP 요청의 트랜잭션이 끝난 후 새 트랜잭션으로 조회한다.
        Snapshot after = snapshot();

        assertThat(after.profileStatus())
                .isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(after.sourceVersion()).isEqualTo(2);
        assertThat(after.analyzedSourceVersion()).isEqualTo(2);
        assertThat(after.pendingSince()).isNull();
        // 변경 대기가 남아 있으므로 늦은 완료 콜백도 재시작 횟수를 보존한다.
        assertThat(after.retryCount()).isEqualTo(1);

        assertThat(after.recommendations())
                .extracting(RecommendationRow::productId)
                .containsExactlyElementsOf(requestedIds);

        assertThat(after.recommendations())
                .extracting(RecommendationRow::rankOrder)
                .containsExactly(1, 2);

        assertThat(after.recommendations())
                .extracting(RecommendationRow::sourceVersion)
                .containsExactly(2L, 2L);

        // 콜백 완료는 사용자 변경 기록을 제거하지 않는다.
        assertThat(after.lastChangedAt())
                .isEqualTo(beforeCallback.lastChangedAt());
        assertThat(after.windowStartedAt())
                .isEqualTo(beforeCallback.windowStartedAt());

        List<Long> oldRowIds = beforeCallback.recommendations().stream()
                .map(RecommendationRow::id)
                .toList();

        assertThat(after.recommendations())
                .extracting(RecommendationRow::id)
                .doesNotContainAnyElementsOf(oldRowIds);
    }

    @Test
    @DisplayName("기존 추천 삭제 후 저장 실패 시 기존 행과 상태를 복구한다")
    void callback_rollsBackDeletionWhenSavingFails()
            throws Exception {

        // 서비스의 기존 추천 삭제는 실제 실행된다.
        // 그 다음 호출되는 추천 저장 단계에서 실패시킨다.
        doThrow(new DataAccessResourceFailureException(
                "테스트용 추천 저장 실패"
        ))
                .when(recommendationRepository)
                .saveAllAndFlush(anyList());

        expectInternalServerError(
                send(2, replacementProductIds())
        );

        // 기존 추천 행의 ID까지 같아야 한다.
        assertThat(snapshot()).isEqualTo(beforeCallback);
    }

    @Test
    @DisplayName("추천 삽입과 프로파일 변경 후 커밋 실패 시 전체 롤백한다")
    void callback_rollsBackAllChangesWhenCommitFails()
            throws Exception {

        doAnswer(invocation -> {
            // 실제 추천 저장을 먼저 실행한다.
            Object result = invocation.callRealMethod();

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {

                        @Override
                        public void beforeCommit(boolean readOnly) {
                            // 서비스에서 변경한 프로파일 상태도 DB에 반영한다.
                            entityManager.flush();

                            // 실제 커밋 전에 실패시켜 전체 롤백을 검증한다.
                            throw new DataAccessResourceFailureException(
                                    "테스트용 커밋 직전 실패"
                            );
                        }
                    }
            );

            return result;
        })
                .when(recommendationRepository)
                .saveAllAndFlush(anyList());

        expectInternalServerError(
                send(2, replacementProductIds())
        );

        assertThat(snapshot()).isEqualTo(beforeCallback);
    }

    @Test
    @DisplayName("잘못된 상품이 포함되면 기존 추천과 프로파일을 유지한다")
    void callback_preservesExistingResultsForInvalidProduct()
            throws Exception {

        send(
                2,
                List.of(productIds.get(2), Long.MAX_VALUE)
        )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code")
                        .value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty());

        assertThat(snapshot()).isEqualTo(beforeCallback);
    }

    @Test
    @DisplayName("저장된 이전 버전 재전송은 최신 대기 상태를 변경하지 않는다")
    void callback_keepsPendingStateForAlreadySavedVersion()
            throws Exception {

        send(
                1,
                List.of(productIds.get(0), productIds.get(1))
        )
                .andExpect(status().isOk());

        assertThat(snapshot()).isEqualTo(beforeCallback);
    }

    private Product createProduct(
            Category category,
            String name,
            long price
    ) {
        return productRepository.saveAndFlush(new Product(
                category,
                name,
                "브랜드",
                null,
                BigDecimal.valueOf(price),
                10
        ));
    }

    private List<Long> replacementProductIds() {
        return List.of(productIds.get(2), productIds.get(0));
    }

    private ResultActions send(
            long version,
            List<Long> ids
    ) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "recipientUserId", recipientId,
                "sourceVersion", version,
                "profileStatus", "COMPLETED",
                "recommendedProductIds", ids
        ));

        return mockMvc.perform(
                post(
                        "/api/internal/v1/recipients/"
                                + recipientId
                                + "/profile"
                )
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + SERVICE_TOKEN
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
        );
    }

    private void expectInternalServerError(
            ResultActions result
    ) throws Exception {
        result
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code")
                        .value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private Snapshot snapshot() {
        return transactions.execute(status -> {
            RecipientProfile profile = profileRepository
                    .findByRecipient_Id(recipientId)
                    .orElseThrow();

            // 버전 조건 없이 해당 수신자의 모든 추천 행을 확인한다.
            // 잘못 남은 새 버전 행도 검출하기 위한 조회다.
            List<RecommendationRow> rows = entityManager.createQuery("""
                            select recommendation
                            from RecipientRecommendedProduct recommendation
                            where recommendation.recipientProfile.recipient.id
                                  = :recipientId
                            order by recommendation.sourceVersion,
                                     recommendation.rankOrder
                            """, RecipientRecommendedProduct.class)
                    .setParameter("recipientId", recipientId)
                    .getResultList()
                    .stream()
                    .map(row -> new RecommendationRow(
                            row.getId(),
                            row.getProduct().getId(),
                            row.getRankOrder(),
                            row.getSourceVersion()
                    ))
                    .toList();

            return new Snapshot(
                    profile.getProfileStatus(),
                    profile.getSourceVersion(),
                    profile.getAnalyzedSourceVersion(),
                    profile.getPendingSince(),
                    profile.getRetryCount(),
                    profile.getLastChangedAt(),
                    profile.getWindowStartedAt(),
                    rows
            );
        });
    }

    private record RecommendationRow(
            Long id,
            Long productId,
            int rankOrder,
            long sourceVersion
    ) {
    }

    private record Snapshot(
            RecipientProfileStatus profileStatus,
            long sourceVersion,
            long analyzedSourceVersion,
            LocalDateTime pendingSince,
            int retryCount,
            LocalDateTime lastChangedAt,
            LocalDateTime windowStartedAt,
            List<RecommendationRow> recommendations
    ) {
    }
}
