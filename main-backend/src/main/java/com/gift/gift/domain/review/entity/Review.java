package com.gift.gift.domain.review.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(name = "reviews")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends BaseTimeEntity {

    private static final int MIN_RATING = 1;
    private static final int MAX_RATING = 5;
    private static final int MAX_REVIEW_TEXT_LENGTH = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "gift_history_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_reviews_gift_history")
    )
    private GiftHistory giftHistory;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_reviews_user")
    )
    private User user;

    @Column(
            name = "rating",
            nullable = false,
            columnDefinition = "SMALLINT"
    )
    private Integer rating;

    @Column(
            name = "review_text",
            columnDefinition = "TEXT"
    )
    private String reviewText;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Review(
            GiftHistory giftHistory,
            User user,
            Integer rating,
            String reviewText
    ) {
        if (giftHistory == null) {
            throw new IllegalArgumentException("리뷰 대상 선물은 필수입니다.");
        }

        if (user == null) {
            throw new IllegalArgumentException("리뷰 작성자는 필수입니다.");
        }

        this.giftHistory = giftHistory;
        this.user = user;
        this.rating = validateRating(rating);
        this.reviewText = normalizeAndValidateReviewText(reviewText);
    }

    public void updateRating(Integer rating) {
        validateActive();
        this.rating = validateRating(rating);
    }

    public void updateContent(String reviewText) {
        validateActive();
        this.reviewText = normalizeAndValidateReviewText(reviewText);
    }

    public void softDelete(LocalDateTime deletedAt) {
        validateActive();

        if (deletedAt == null) {
            throw new IllegalArgumentException("리뷰 삭제 시각은 필수입니다.");
        }

        this.deletedAt = deletedAt;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private void validateActive() {
        if (isDeleted()) {
            throw new IllegalStateException("삭제된 리뷰는 변경할 수 없습니다.");
        }
    }

    private Integer validateRating(Integer rating) {
        if (rating == null) {
            throw new IllegalArgumentException("별점은 필수입니다.");
        }

        if (rating < MIN_RATING || rating > MAX_RATING) {
            throw new IllegalArgumentException("별점은 1에서 5 사이여야 합니다.");
        }

        return rating;
    }

    private String normalizeAndValidateReviewText(String reviewText) {
        if (reviewText == null || reviewText.isBlank()) {
            return null;
        }

        int length = reviewText.codePointCount(0, reviewText.length());

        if (length > MAX_REVIEW_TEXT_LENGTH) {
            throw new IllegalArgumentException("리뷰 내용은 300자를 넘을 수 없습니다.");
        }

        return reviewText;
    }
}
