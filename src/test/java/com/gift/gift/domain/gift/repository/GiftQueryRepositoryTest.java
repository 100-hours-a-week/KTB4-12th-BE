package com.gift.gift.domain.gift.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class GiftQueryRepositoryTest {

    @Autowired
    private GiftQueryRepository giftQueryRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("보낸·받은 선물 목록과 상세를 각 사용자 관점으로 조회한다")
    void findGifts_projectsListAndDetailForEachDirection() {
        Fixture fixture = persistFixture();

        List<GiftQueryRow> sentGifts = giftQueryRepository.findSentGifts(fixture.senderId(), null);
        List<GiftQueryRow> receivedGifts = giftQueryRepository.findReceivedGifts(fixture.recipientId(), null);

        assertThat(sentGifts).singleElement().satisfies(row -> {
            assertThat(row.giftId()).isEqualTo(fixture.giftId());
            assertThat(row.counterpartUserId()).isEqualTo(fixture.recipientId());
            assertThat(row.counterpartName()).isEqualTo("받는사람");
            assertThat(row.productId()).isEqualTo(fixture.productId());
            assertThat(row.productNameSnapshot()).isEqualTo("선물 상품");
            assertThat(row.productPriceSnapshot()).isEqualByComparingTo("10000");
            assertThat(row.quantity()).isEqualTo(2);
            assertThat(row.productBrand()).isEqualTo("선물 브랜드");
        });
        assertThat(receivedGifts).singleElement().satisfies(row -> {
            assertThat(row.giftId()).isEqualTo(fixture.giftId());
            assertThat(row.counterpartUserId()).isEqualTo(fixture.senderId());
            assertThat(row.counterpartName()).isEqualTo("보낸사람");
        });
        assertThat(giftQueryRepository.findSentGiftDetail(fixture.giftId(), fixture.senderId())).isPresent();
        assertThat(giftQueryRepository.findReceivedGiftDetail(fixture.giftId(), fixture.recipientId())).isPresent();
        assertThat(giftQueryRepository.findSentGiftDetail(fixture.giftId(), fixture.recipientId())).isEmpty();
        assertThat(giftQueryRepository.findReceivedGiftDetail(fixture.giftId(), fixture.senderId())).isEmpty();
    }

    @Test
    @DisplayName("복합 커서 이후의 선물만 조회한다")
    void findGifts_excludesCursorAndNewerRows() {
        Fixture fixture = persistFixture();

        GiftCursor cursor = new GiftCursor(fixture.completedAt(), fixture.giftId());

        assertThat(giftQueryRepository.findSentGifts(fixture.senderId(), cursor)).isEmpty();
        assertThat(giftQueryRepository.findReceivedGifts(fixture.recipientId(), cursor)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 사용자와 삭제 상품이 포함된 과거 선물 이력을 유지한다")
    void findGifts_keepsHistoryForDeletedCounterpartAndProduct() {
        Fixture fixture = persistFixture();
        LocalDateTime deletedAt = LocalDateTime.now();
        jdbcTemplate.update("UPDATE users SET deleted_at = ? WHERE id = ?", deletedAt, fixture.recipientId());
        jdbcTemplate.update("UPDATE products SET deleted_at = ? WHERE id = ?", deletedAt, fixture.productId());
        entityManager.clear();

        GiftQueryRow row = giftQueryRepository.findSentGiftDetail(fixture.giftId(), fixture.senderId()).orElseThrow();

        assertThat(row.counterpartName()).isEqualTo("받는사람");
        assertThat(row.counterpartDeletedAt()).isNotNull();
        assertThat(row.productNameSnapshot()).isEqualTo("선물 상품");
        assertThat(row.productBrand()).isNull();
    }

    private Fixture persistFixture() {
        String uniqueSuffix = UUID.randomUUID().toString();
        Category rootCategory = new Category("대분류-" + uniqueSuffix, null);
        Category leafCategory = new Category("소분류-" + uniqueSuffix, rootCategory);
        User sender = new User(
                "sender-" + uniqueSuffix + "@example.com",
                "$2a$10$" + "a".repeat(53),
                "보낸사람",
                LocalDate.of(1990, 1, 1)
        );
        User recipient = new User(
                "recipient-" + uniqueSuffix + "@example.com",
                "$2a$10$" + "b".repeat(53),
                "받는사람",
                LocalDate.of(1991, 1, 1)
        );
        Product product = new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                10
        );
        GiftHistory giftHistory = new GiftHistory(
                sender,
                recipient,
                product,
                2,
                BigDecimal.valueOf(10_000),
                "선물 상품",
                UUID.randomUUID(),
                "a".repeat(64)
        );

        entityManager.persist(rootCategory);
        entityManager.persist(leafCategory);
        entityManager.persist(sender);
        entityManager.persist(recipient);
        entityManager.persist(product);
        entityManager.persist(giftHistory);
        entityManager.flush();
        entityManager.clear();

        return new Fixture(
                giftHistory.getId(),
                giftHistory.getCompletedAt(),
                sender.getId(),
                recipient.getId(),
                product.getId()
        );
    }

    private record Fixture(
            Long giftId,
            LocalDateTime completedAt,
            Long senderId,
            Long recipientId,
            Long productId
    ) {
    }
}
