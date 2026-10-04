package com.gift.gift.domain.review.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.review.entity.Review;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class ReviewPersistenceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    @DisplayName("리뷰를 저장하면 식별자와 감사 시각이 생성된다")
    void save_persistsReview() {
        Fixture fixture = persistFixture();

        Review saved = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "좋아요"
                )
        );

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getGiftHistory())
                .isSameAs(fixture.giftHistory());
        assertThat(saved.getUser())
                .isSameAs(fixture.recipient());
        assertThat(saved.getRating()).isEqualTo(5);
        assertThat(saved.getReviewText()).isEqualTo("좋아요");
        assertThat(saved.getDeletedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 선물의 활성 리뷰는 두 건 저장할 수 없다")
    void save_rejectsSecondActiveReviewForSameGift() {
        Fixture fixture = persistFixture();

        reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "첫 번째 리뷰"
                )
        );

        Review duplicate = new Review(
                fixture.giftHistory(),
                fixture.recipient(),
                4,
                "중복 리뷰"
        );

        assertThatThrownBy(
                () -> reviewRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("리뷰 삭제 후 같은 선물에 새 리뷰를 저장할 수 있다")
    void save_allowsNewReviewAfterPreviousReviewIsDeleted() {
        Fixture fixture = persistFixture();

        Review deletedReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "첫 번째 리뷰"
                )
        );

        deletedReview.softDelete(
                LocalDateTime.of(2026, 10, 3, 12, 0)
        );
        reviewRepository.saveAndFlush(deletedReview);

        Review recreatedReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        4,
                        "재등록한 리뷰"
                )
        );

        assertThat(recreatedReview.getId())
                .isNotEqualTo(deletedReview.getId());
        assertThat(deletedReview.getDeletedAt()).isNotNull();
        assertThat(recreatedReview.getDeletedAt()).isNull();
        assertThat(reviewRepository.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("같은 선물의 삭제된 리뷰 행은 여러 건 보관할 수 있다")
    void save_allowsMultipleDeletedReviewsForSameGift() {
        Fixture fixture = persistFixture();

        Review firstReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "첫 번째 리뷰"
                )
        );
        firstReview.softDelete(
                LocalDateTime.of(2026, 10, 3, 12, 0)
        );
        reviewRepository.saveAndFlush(firstReview);

        Review secondReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        4,
                        "두 번째 리뷰"
                )
        );
        secondReview.softDelete(
                LocalDateTime.of(2026, 10, 3, 13, 0)
        );
        reviewRepository.saveAndFlush(secondReview);

        Review activeReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        3,
                        "현재 활성 리뷰"
                )
        );

        entityManager.flush();
        entityManager.clear();

        assertThat(reviewRepository.count()).isEqualTo(3);

        Review savedActiveReview = reviewRepository.findById(
                activeReview.getId()
        ).orElseThrow();

        assertThat(savedActiveReview.getDeletedAt()).isNull();
        assertThat(savedActiveReview.getReviewText())
                .isEqualTo("현재 활성 리뷰");
    }

    @Test
    @DisplayName("선물과 작성자로 활성 리뷰를 조회한다")
    void findActiveReview_returnsReview() {
        Fixture fixture = persistFixture();

        Review activeReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "현재 리뷰"
                )
        );

        entityManager.clear();

        Review foundReview = reviewRepository
                .findByGiftHistory_IdAndUser_IdAndDeletedAtIsNull(
                        fixture.giftHistory().getId(),
                        fixture.recipient().getId()
                )
                .orElseThrow();

        assertThat(foundReview.getId()).isEqualTo(activeReview.getId());
        assertThat(foundReview.getReviewText()).isEqualTo("현재 리뷰");
        assertThat(foundReview.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제된 리뷰는 활성 리뷰 조회에서 제외한다")
    void findActiveReview_ignoresDeletedReview() {
        Fixture fixture = persistFixture();

        Review deletedReview = reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "삭제된 리뷰"
                )
        );

        deletedReview.softDelete(
                LocalDateTime.of(2026, 10, 3, 12, 0)
        );
        reviewRepository.saveAndFlush(deletedReview);
        entityManager.clear();

        assertThat(
                reviewRepository
                        .findByGiftHistory_IdAndUser_IdAndDeletedAtIsNull(
                                fixture.giftHistory().getId(),
                                fixture.recipient().getId()
                        )
        ).isEmpty();
    }

    @Test
    @DisplayName("다른 사용자의 ID로는 활성 리뷰를 조회할 수 없다")
    void findActiveReview_rejectsDifferentUser() {
        Fixture fixture = persistFixture();

        reviewRepository.saveAndFlush(
                new Review(
                        fixture.giftHistory(),
                        fixture.recipient(),
                        5,
                        "수신자의 리뷰"
                )
        );

        entityManager.clear();

        assertThat(
                reviewRepository
                        .findByGiftHistory_IdAndUser_IdAndDeletedAtIsNull(
                                fixture.giftHistory().getId(),
                                fixture.sender().getId()
                        )
        ).isEmpty();
    }

    private Fixture persistFixture() {
        String suffix = UUID.randomUUID().toString();

        Category rootCategory = new Category("대분류-" + suffix, null);
        entityManager.persist(rootCategory);

        Category leafCategory = new Category("소분류-" + suffix, rootCategory);
        entityManager.persist(leafCategory);

        User sender = newUser("sender-" + suffix);
        User recipient = newUser("recipient-" + suffix);
        entityManager.persist(sender);
        entityManager.persist(recipient);

        Product product = new Product(
                leafCategory,
                "선물 상품",
                "선물 브랜드",
                null,
                BigDecimal.valueOf(10_000),
                100
        );
        entityManager.persist(product);
        entityManager.flush();

        GiftHistory giftHistory = new GiftHistory(
                sender,
                recipient,
                product,
                1,
                BigDecimal.valueOf(10_000),
                "선물 상품",
                UUID.randomUUID(),
                "a".repeat(64)
        );
        entityManager.persist(giftHistory);
        entityManager.flush();

        return new Fixture(
                giftHistory,
                sender,
                recipient
        );
    }

    private User newUser(String emailPrefix) {
        return new User(
                emailPrefix + "@example.com",
                PASSWORD_HASH,
                "김선물",
                LocalDate.of(2000, 1, 1)
        );
    }

    private record Fixture(
            GiftHistory giftHistory,
            User sender,
            User recipient
    ) {
    }
}
