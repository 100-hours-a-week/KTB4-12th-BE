package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.entity.ProductImage;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.pagination.CursorPageResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class GiftQueryServiceIntegrationTest {

    @TestConfiguration
    static class PresignerTestConfig {

        // Presigned URL 서명은 로컬 계산이라 값은 무관하지만, 자격 증명이 없는 CI에서는 기본 체인이 실패한다.
        @Bean
        @Primary
        S3Presigner testS3Presigner(@Value("${storage.s3.region}") String region) {
            return S3Presigner.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("test-access-key", "test-secret-key")
                    ))
                    .build();
        }
    }

    @Autowired
    private GiftQueryService giftQueryService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("보낸·받은 선물 목록은 다음 커서로 마지막 페이지까지 조회한다")
    void getGifts_returnsNextAndLastPagesForEachDirection() {
        PageFixture fixture = persistPageFixture(21);

        CursorPageResponse<SentGiftListItem> firstSentPage = giftQueryService.getSentGifts(fixture.senderId(), null);
        CursorPageResponse<SentGiftListItem> lastSentPage = giftQueryService.getSentGifts(
                fixture.senderId(),
                firstSentPage.pagination().nextCursor()
        );
        CursorPageResponse<ReceivedGiftListItem> firstReceivedPage = giftQueryService.getReceivedGifts(
                fixture.recipientId(),
                null
        );
        CursorPageResponse<ReceivedGiftListItem> lastReceivedPage = giftQueryService.getReceivedGifts(
                fixture.recipientId(),
                firstReceivedPage.pagination().nextCursor()
        );

        assertThat(firstSentPage.items()).extracting(SentGiftListItem::giftId)
                .containsExactlyElementsOf(fixture.giftIds().subList(0, 20));
        assertThat(firstSentPage.pagination().hasNext()).isTrue();
        assertThat(firstSentPage.pagination().nextCursor()).isNotBlank();
        assertThat(firstSentPage.items()).allSatisfy(item ->
                assertThat(item.product().thumbnailUrl()).isNull());
        assertThat(lastSentPage.items()).extracting(SentGiftListItem::giftId)
                .containsExactly(fixture.giftIds().getLast());
        assertThat(lastSentPage.pagination().hasNext()).isFalse();
        assertThat(lastSentPage.pagination().nextCursor()).isNull();

        assertThat(firstReceivedPage.items()).extracting(ReceivedGiftListItem::giftId)
                .containsExactlyElementsOf(fixture.giftIds().subList(0, 20));
        assertThat(firstReceivedPage.pagination().hasNext()).isTrue();
        assertThat(firstReceivedPage.pagination().nextCursor()).isNotBlank();
        assertThat(lastReceivedPage.items()).extracting(ReceivedGiftListItem::giftId)
                .containsExactly(fixture.giftIds().getLast());
        assertThat(lastReceivedPage.pagination().hasNext()).isFalse();
        assertThat(lastReceivedPage.pagination().nextCursor()).isNull();
    }

    @Test
    @DisplayName("보낸·받은 선물 목록 조회 쿼리 수는 데이터 수와 무관하게 두 번이다")
    void getGifts_executesTwoQueriesRegardlessOfItemCount() {
        PageFixture singleGiftFixture = persistPageFixture(1);
        PageFixture fullPageFixture = persistPageFixture(21);
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        giftQueryService.getSentGifts(singleGiftFixture.senderId(), null);
        long singleSentGiftQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        giftQueryService.getSentGifts(fullPageFixture.senderId(), null);
        long fullSentPageQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        giftQueryService.getReceivedGifts(singleGiftFixture.recipientId(), null);
        long singleReceivedGiftQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        giftQueryService.getReceivedGifts(fullPageFixture.recipientId(), null);
        long fullReceivedPageQueryCount = statistics.getPrepareStatementCount();

        assertThat(singleSentGiftQueryCount).isEqualTo(2);
        assertThat(fullSentPageQueryCount).isEqualTo(2);
        assertThat(singleReceivedGiftQueryCount).isEqualTo(2);
        assertThat(fullReceivedPageQueryCount).isEqualTo(2);
    }

    @Test
    @DisplayName("대표 이미지는 정렬 우선순위로 선택하고 삭제 이미지는 제외하되 삭제 상품의 이미지는 유지한다")
    void getGifts_appliesProductRepresentativeImagePolicy() {
        PageFixture fixture = persistPageFixture(1);
        Product product = entityManager.find(Product.class, fixture.productId());
        String suffix = UUID.randomUUID().toString();
        ProductImage primaryImage = new ProductImage(product, "products/" + suffix + "/primary.jpg", 1);
        ProductImage fallbackImage = new ProductImage(product, "products/" + suffix + "/fallback.jpg", 2);
        entityManager.persist(primaryImage);
        entityManager.persist(fallbackImage);
        entityManager.flush();
        entityManager.clear();

        CursorPageResponse<SentGiftListItem> sentPage = giftQueryService.getSentGifts(fixture.senderId(), null);

        assertThat(sentPage.items().getFirst().product().thumbnailUrl())
                .contains("primary.jpg");

        jdbcTemplate.update(
                "UPDATE product_images SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                primaryImage.getId()
        );
        jdbcTemplate.update(
                "UPDATE products SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                fixture.productId()
        );
        entityManager.clear();

        String receivedImageUrl = giftQueryService.getReceivedGiftDetail(
                fixture.recipientId(),
                fixture.giftIds().getFirst()
        ).product().imageUrl();

        assertThat(receivedImageUrl).contains("fallback.jpg");
    }

    @Test
    @DisplayName("선물 이력이 없으면 보낸·받은 선물 목록은 빈 페이지를 반환한다")
    void getGifts_returnsEmptyPages_whenUserHasNoGiftHistory() {
        Long userId = persistUserWithoutGift();

        CursorPageResponse<SentGiftListItem> sentPage = giftQueryService.getSentGifts(userId, null);
        CursorPageResponse<ReceivedGiftListItem> receivedPage = giftQueryService.getReceivedGifts(userId, null);

        assertThat(sentPage.items()).isEmpty();
        assertThat(sentPage.pagination().hasNext()).isFalse();
        assertThat(sentPage.pagination().nextCursor()).isNull();
        assertThat(receivedPage.items()).isEmpty();
        assertThat(receivedPage.pagination().hasNext()).isFalse();
        assertThat(receivedPage.pagination().nextCursor()).isNull();
    }

    private PageFixture persistPageFixture(int giftCount) {
        String uniqueSuffix = UUID.randomUUID().toString();
        Category rootCategory = new Category("대분류-" + uniqueSuffix, null);
        Category leafCategory = new Category("소분류-" + uniqueSuffix, rootCategory);
        User sender = createUser("sender-" + uniqueSuffix, "보낸사람");
        User recipient = createUser("recipient-" + uniqueSuffix, "받는사람");
        Product product = new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                100
        );

        entityManager.persist(rootCategory);
        entityManager.persist(leafCategory);
        entityManager.persist(sender);
        entityManager.persist(recipient);
        entityManager.persist(product);

        List<GiftHistory> giftHistories = new ArrayList<>();
        for (int index = 0; index < giftCount; index++) {
            GiftHistory giftHistory = new GiftHistory(
                    sender,
                    recipient,
                    product,
                    1,
                    BigDecimal.valueOf(10_000),
                    "선물 상품",
                    UUID.randomUUID(),
                    "%064d".formatted(index)
            );
            entityManager.persist(giftHistory);
            giftHistories.add(giftHistory);
        }
        entityManager.flush();

        LocalDateTime latestCompletedAt = LocalDateTime.of(2026, 9, 18, 12, 0);
        for (int index = 0; index < giftHistories.size(); index++) {
            jdbcTemplate.update(
                    "UPDATE gift_histories SET completed_at = ? WHERE id = ?",
                    latestCompletedAt.minusMinutes(index),
                    giftHistories.get(index).getId()
            );
        }
        List<Long> giftIds = giftHistories.stream().map(GiftHistory::getId).toList();
        entityManager.clear();

        return new PageFixture(sender.getId(), recipient.getId(), product.getId(), giftIds);
    }

    private Long persistUserWithoutGift() {
        String uniqueSuffix = UUID.randomUUID().toString();
        User user = createUser("empty-" + uniqueSuffix, "선물없는사람");
        entityManager.persist(user);
        entityManager.flush();
        entityManager.clear();

        return user.getId();
    }

    private User createUser(String emailPrefix, String name) {
        return new User(
                emailPrefix + "@example.com",
                "$2a$10$" + "a".repeat(53),
                name,
                LocalDate.of(1990, 1, 1)
        );
    }

    private record PageFixture(
            Long senderId,
            Long recipientId,
            Long productId,
            List<Long> giftIds
    ) {
    }
}
