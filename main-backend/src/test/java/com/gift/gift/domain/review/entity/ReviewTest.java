package com.gift.gift.domain.review.entity;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ReviewTest {

    private final GiftHistory giftHistory = mock(GiftHistory.class);
    private final User user = mock(User.class);

    @Test
    @DisplayName("정상 값으로 리뷰를 생성한다")
    void constructor_createsReview() {
        Review review = new Review(
                giftHistory,
                user,
                5,
                "좋아요"
        );

        assertThat(review.getGiftHistory()).isSameAs(giftHistory);
        assertThat(review.getUser()).isSameAs(user);
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getReviewText()).isEqualTo("좋아요");
        assertThat(review.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("리뷰 내용이 null이면 본문 없이 생성한다")
    void constructor_allowsNullReviewText() {
        Review review = new Review(giftHistory, user, 5, null);

        assertThat(review.getReviewText()).isNull();
    }

    @Test
    @DisplayName("빈 문자열 리뷰 내용은 null로 정규화한다")
    void constructor_normalizesEmptyReviewTextToNull() {
        Review review = new Review(giftHistory, user, 5, "");

        assertThat(review.getReviewText()).isNull();
    }

    @Test
    @DisplayName("리뷰 대상 선물이 null이면 생성할 수 없다")
    void constructor_rejectsNullGiftHistory() {
        assertThatThrownBy(
                () -> new Review(null, user, 5, "좋아요")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("리뷰 대상 선물은 필수입니다.");
    }

    @Test
    @DisplayName("리뷰 작성자가 null이면 생성할 수 없다")
    void constructor_rejectsNullUser() {
        assertThatThrownBy(
                () -> new Review(giftHistory, null, 5, "좋아요")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("리뷰 작성자는 필수입니다.");
    }

    @Test
    @DisplayName("별점이 null이면 생성할 수 없다")
    void constructor_rejectsNullRating() {
        assertThatThrownBy(
                () -> new Review(giftHistory, user, null, "좋아요")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("별점은 필수입니다.");
    }

    @Test
    @DisplayName("별점이 1보다 작으면 생성할 수 없다")
    void constructor_rejectsRatingLessThanOne() {
        assertThatThrownBy(
                () -> new Review(giftHistory, user, 0, "좋아요")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("별점은 1에서 5 사이여야 합니다.");
    }

    @Test
    @DisplayName("별점이 5보다 크면 생성할 수 없다")
    void constructor_rejectsRatingGreaterThanFive() {
        assertThatThrownBy(
                () -> new Review(giftHistory, user, 6, "좋아요")
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("별점은 1에서 5 사이여야 합니다.");
    }

    @Test
    @DisplayName("공백으로만 이루어진 리뷰 내용은 null로 정규화한다")
    void constructor_normalizesBlankReviewTextToNull() {
        Review review = new Review(giftHistory, user, 5, " \t\n");

        assertThat(review.getReviewText()).isNull();
    }

    @Test
    @DisplayName("리뷰 내용이 유니코드 코드 포인트 기준 300자를 넘으면 거부한다")
    void constructor_rejectsReviewTextOver300UnicodeCodePoints() {
        String reviewText = "😊".repeat(301);

        assertThatThrownBy(
                () -> new Review(
                        giftHistory,
                        user,
                        5,
                        reviewText
                )
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("리뷰 내용은 300자를 넘을 수 없습니다.");
    }

    @Test
    @DisplayName("활성 리뷰의 별점을 수정한다")
    void updateRating_updatesRating() {
        Review review = new Review(
                giftHistory,
                user,
                5,
                "좋아요"
        );

        review.updateRating(3);

        assertThat(review.getRating()).isEqualTo(3);
    }

    @Test
    @DisplayName("활성 리뷰의 내용을 수정한다")
    void updateContent_updatesReviewText() {
        Review review = new Review(
                giftHistory,
                user,
                5,
                "수정 전"
        );

        review.updateContent("수정 후");

        assertThat(review.getReviewText()).isEqualTo("수정 후");
    }

    @Test
    @DisplayName("리뷰 내용을 빈 문자열로 수정하면 본문을 삭제한다")
    void updateContent_normalizesEmptyReviewTextToNull() {
        Review review = new Review(
                giftHistory,
                user,
                5,
                "기존 내용"
        );

        review.updateContent("");

        assertThat(review.getReviewText()).isNull();
    }

    @Test
    @DisplayName("삭제된 리뷰는 별점을 수정할 수 없다")
    void updateRating_rejectsDeletedReview() {
        Review review = deletedReview();

        assertThatThrownBy(
                () -> review.updateRating(3)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("삭제된 리뷰는 변경할 수 없습니다.");
    }

    @Test
    @DisplayName("삭제된 리뷰는 내용을 수정할 수 없다")
    void updateContent_rejectsDeletedReview() {
        Review review = deletedReview();

        assertThatThrownBy(
                () -> review.updateContent("수정 내용")
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("삭제된 리뷰는 변경할 수 없습니다.");
    }

    private Review deletedReview() {
        Review review = new Review(
                giftHistory,
                user,
                5,
                "좋아요"
        );
        review.softDelete(LocalDateTime.of(2026, 10, 3, 12, 0));
        return review;
    }
}
