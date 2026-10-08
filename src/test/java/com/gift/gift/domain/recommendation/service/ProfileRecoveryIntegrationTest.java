package com.gift.gift.domain.recommendation.service;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.preference.service.PreferenceSaveService;
import com.gift.gift.domain.product.dto.request.ProductListRequest;
import com.gift.gift.domain.product.dto.response.ProductSummaryResponse;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.repository.ProductSort;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.exception.AiProfilingFailureType;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.infrastructure.ai.AiProfilingClient;
import com.gift.gift.domain.user.entity.User;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = {"app.scheduling.enabled=false", "app.ai-profile.scheduling-enabled=false",
        "app.ai-profile.recovery-batch-size=1"})
class ProfileRecoveryIntegrationTest {
    // 다른 통합 테스트가 커밋한 최근 PENDING 행과 후보 시간을 분리한다.
    private static final Instant NOW = Instant.parse("2001-01-01T03:00:00Z");
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecipientProfileRepository profiles;
    @Autowired private ProfileDispatchService dispatch;
    @Autowired private ProfileCallbackService callbacks;
    @Autowired private PreferenceSaveService preferences;
    @Autowired private ProductQueryService products;
    @MockitoBean private AiProfilingClient client;
    @MockitoBean private Clock clock;
    private TransactionTemplate tx;
    private Long ownerId, recipientId, rootId, childId, firstId, secondId;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        setTime(NOW);
        when(client.isHealthy()).thenReturn(true);
        doAnswer(call -> accepted(call.getArgument(0))).when(client).requestProfiling(any());
        tx.executeWithoutResult(status -> {
            User owner = user();
            User recipient = user();
            ownerId = owner.getId();
            recipientId = recipient.getId();
            entityManager.persist(new Friend(owner, recipient));
            Category root = new Category("복구-" + UUID.randomUUID(), null);
            entityManager.persist(root);
            rootId = root.getId();
            Category child = new Category("복구 세부-" + UUID.randomUUID(), root);
            entityManager.persist(child);
            childId = child.getId();
            firstId = product(child, "추천 상품").getId();
            secondId = product(child, "인기 상품").getId();
            entityManager.flush();
            sql("UPDATE products SET views = 100 WHERE id = :id", secondId);
        });
    }

    @AfterEach
    void tearDown() {
        if (recipientId == null) return;
        tx.executeWithoutResult(status -> {
            sql("DELETE FROM recipient_recommended_products WHERE recipient_id = :id", recipientId);
            sql("DELETE FROM recipient_profiles WHERE recipient_id = :id", recipientId);
            sql("DELETE FROM user_dislike_categories WHERE user_id = :id", recipientId);
            sql("DELETE FROM friends WHERE user_id = :id OR friend_user_id = :id", ownerId);
            sql("DELETE FROM products WHERE id = :id", firstId);
            sql("DELETE FROM products WHERE id = :id", secondId);
            sql("DELETE FROM categories WHERE id = :id", childId);
            sql("DELETE FROM categories WHERE id = :id", rootId);
            sql("DELETE FROM users WHERE id = :id", recipientId);
            sql("DELETE FROM users WHERE id = :id", ownerId);
        });
    }

    @Test
    void preferenceToSameVersionRecoveryCallbackAndProductQuery() {
        setTime(NOW.minusSeconds(3601));
        preferences.saveDislikeCategories(recipientId, List.of(rootId));
        setTime(NOW);
        List<AiProfileRequest> requests = new ArrayList<>();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            AiProfileRequest request = call.getArgument(0);
            assertThat(profile().getSourceVersion()).isEqualTo(request.sourceVersion());
            assertThat(request.dislikedCategories()).extracting(item -> item.categoryId()).containsExactly(rootId);
            requests.add(request);
            return accepted(request);
        }).when(client).requestProfiling(any());
        dispatch.dispatchDueProfiles();
        setTime(NOW.plusSeconds(6 * 3600 - 1));
        dispatch.dispatchDueProfiles();
        assertThat(requests).hasSize(1);
        setTime(NOW.plusSeconds(6 * 3600));
        dispatch.dispatchDueProfiles();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(1).sourceVersion()).isEqualTo(requests.get(0).sourceVersion());
        assertThat(profile().getPendingSince()).isEqualTo(local(NOW.plusSeconds(6 * 3600)));
        callbacks.saveCallback(recipientId, recipientId, 1, List.of(firstId));
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        var response = products.getProducts(request(), ownerId);
        assertThat(response.appliedSort()).isEqualTo(ProductSort.AI_RECOMMENDED);
        assertThat(response.products()).extracting(ProductSummaryResponse::productId).containsExactly(firstId, secondId);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callbackDuringRecoveryPreservesCompletedState(boolean failure) {
        persistPending();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            callbacks.saveCallback(recipientId, recipientId, 1, List.of(firstId));
            if (failure) throw new AiProfilingClientException(AiProfilingFailureType.AI_SERVICE_UNAVAILABLE, "test failure");
            return accepted(call.getArgument(0));
        }).when(client).requestProfiling(any());
        dispatch.dispatchDueProfiles();
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(profile().getPendingSince()).isNull();
        assertThat(profile().getAnalyzedSourceVersion()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyOrDroppedResultsFallBackToPopular(boolean dropped) {
        persistPending();
        callbacks.saveCallback(recipientId, recipientId, 1, dropped ? List.of(Long.MAX_VALUE) : List.of());
        var response = products.getProducts(request(), ownerId);
        assertThat(profile().getProfileStatus()).isEqualTo(RecipientProfileStatus.COMPLETED);
        assertThat(response.appliedSort()).isEqualTo(ProductSort.POPULAR);
        assertThat(response.products()).extracting(ProductSummaryResponse::productId).containsExactly(secondId, firstId);
    }

    private void persistPending() {
        tx.executeWithoutResult(status -> {
            RecipientProfile profile = new RecipientProfile(entityManager.find(User.class, recipientId));
            profile.createNextSourceVersion();
            profile.markPending(local(NOW.minusSeconds(7 * 3600)));
            entityManager.persist(profile);
        });
    }
    private RecipientProfile profile() { return profiles.findByRecipient_Id(recipientId).orElseThrow(); }
    private ProductListRequest request() { return new ProductListRequest(null, List.of(childId), ProductSort.AI_RECOMMENDED, recipientId, null); }
    private void setTime(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }
    private LocalDateTime local(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private User user() {
        User user = new User(UUID.randomUUID() + "@test.com", "$2a$10$" + "a".repeat(53), "테스트", LocalDate.of(2000, 1, 1));
        entityManager.persist(user);
        return user;
    }
    private Product product(Category child, String name) {
        Product product = new Product(child, name, "브랜드", null, BigDecimal.valueOf(10000), 10);
        entityManager.persist(product);
        return product;
    }
    private AiProfileAcceptedResponse accepted(AiProfileRequest request) {
        return new AiProfileAcceptedResponse(request.recipientUserId(), request.sourceVersion(), RecipientProfileStatus.PENDING);
    }
    private void sql(String query, Long id) { entityManager.createNativeQuery(query).setParameter("id", id).executeUpdate(); }
}
