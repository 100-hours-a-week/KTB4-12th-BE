package com.gift.gift.domain.review.service;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.review.dto.request.CreateReviewRequest;
import com.gift.gift.domain.review.dto.response.ReviewResponse;
import com.gift.gift.domain.review.entity.Review;
import com.gift.gift.domain.review.exception.ReviewException;
import com.gift.gift.domain.review.repository.ReviewRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReviewServiceTest {

    private static final Long GIFT_ID = 10L;
    private static final Long USER_ID = 20L;

    private GiftHistoryRepository giftHistoryRepository;
    private ReviewRepository reviewRepository;
    private ReviewService reviewService;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        reviewRepository = mock(ReviewRepository.class);
        reviewService = new ReviewService(giftHistoryRepository, reviewRepository);
    }

    @Test
    @DisplayName("완료된 받은 선물에 수신자를 작성자로 지정해 리뷰를 등록한다")
    void createReview_savesReviewForRecipient() {
        User recipient = mock(User.class);
        GiftHistory giftHistory = mock(GiftHistory.class);
        Review savedReview = savedReview(giftHistory);

        when(giftHistory.getRecipient()).thenReturn(recipient);
        when(giftHistoryRepository
                .findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(
                        GIFT_ID,
                        USER_ID,
                        GiftStatus.COMPLETED
                ))
                .thenReturn(Optional.of(giftHistory));
        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenReturn(savedReview);

        ReviewResponse response = reviewService.createReview(
                GIFT_ID,
                USER_ID,
                new CreateReviewRequest(5, "좋아요")
        );

        assertThat(response.review().reviewId()).isEqualTo(71L);
        assertThat(response.review().giftId()).isEqualTo(GIFT_ID);
        assertThat(response.review().rating()).isEqualTo(5);
        assertThat(response.review().content()).isEqualTo("좋아요");

        ArgumentCaptor<Review> reviewCaptor = ArgumentCaptor.forClass(Review.class);

        verify(reviewRepository).saveAndFlush(reviewCaptor.capture());
        verify(reviewRepository, never()).save(any(Review.class));

        Review reviewToSave = reviewCaptor.getValue();

        assertThat(reviewToSave.getGiftHistory())
                .isSameAs(giftHistory);
        assertThat(reviewToSave.getUser())
                .isSameAs(recipient);
        assertThat(reviewToSave.getRating())
                .isEqualTo(5);
        assertThat(reviewToSave.getReviewText())
                .isEqualTo("좋아요");
    }

    @Test
    @DisplayName("수신자의 완료된 미삭제 선물이 아니면 GIFT_NOT_FOUND를 반환한다")
    void createReview_rejectsUnavailableGift() {
        when(giftHistoryRepository
                .findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(
                        GIFT_ID,
                        USER_ID,
                        GiftStatus.COMPLETED
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewService.createReview(
                GIFT_ID,
                USER_ID,
                new CreateReviewRequest(5, null)
        )).isInstanceOfSatisfying(
                ReviewException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(ErrorCode.GIFT_NOT_FOUND)
        );

        verifyNoInteractions(reviewRepository);
    }

    @Test
    @DisplayName("활성 리뷰 UNIQUE 위반을 REVIEW_ALREADY_EXISTS로 변환한다")
    void createReview_translatesActiveReviewUniqueViolation() {
        arrangeAvailableGift();

        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(constraintViolation(
                        "`gift`.`uk_reviews_active_gift_history`",
                        1062
                ));

        assertThatThrownBy(() -> reviewService.createReview(
                GIFT_ID,
                USER_ID,
                new CreateReviewRequest(5, null)
        )).isInstanceOfSatisfying(
                ReviewException.class,
                exception -> assertThat(
                        exception.getErrorCode()
                ).isEqualTo(ErrorCode.REVIEW_ALREADY_EXISTS)
        );
    }

    @Test
    @DisplayName("예상하지 못한 저장 실패를 리뷰 등록 실패로 변환한다")
    void createReview_translatesUnexpectedFailure() {
        arrangeAvailableGift();

        RuntimeException failure = new RuntimeException("DB 연결 실패");

        when(reviewRepository.saveAndFlush(any(Review.class)))
                .thenThrow(failure);

        assertThatThrownBy(() -> reviewService.createReview(
                GIFT_ID,
                USER_ID,
                new CreateReviewRequest(5, null)
        )).isInstanceOfSatisfying(
                ReviewException.class,
                exception -> {
                    assertThat(exception.getErrorCode())
                            .isEqualTo(
                                    ErrorCode.INTERNAL_SERVER_ERROR
                            );
                    assertThat(exception.getCause())
                            .isSameAs(failure);
                }
        );
    }

    private GiftHistory arrangeAvailableGift() {
        User recipient = mock(User.class);
        GiftHistory giftHistory = mock(GiftHistory.class);

        when(giftHistory.getRecipient())
                .thenReturn(recipient);
        when(giftHistoryRepository
                .findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(
                        GIFT_ID,
                        USER_ID,
                        GiftStatus.COMPLETED
                ))
                .thenReturn(Optional.of(giftHistory));

        return giftHistory;
    }

    private Review savedReview(GiftHistory giftHistory) {
        Review review = mock(Review.class);

        when(review.getId()).thenReturn(71L);
        when(review.getGiftHistory())
                .thenReturn(giftHistory);
        when(giftHistory.getId())
                .thenReturn(GIFT_ID);
        when(review.getRating()).thenReturn(5);
        when(review.getReviewText())
                .thenReturn("좋아요");
        when(review.getCreatedAt()).thenReturn(
                LocalDateTime.of(2026, 10, 3, 12, 0)
        );
        when(review.getUpdatedAt()).thenReturn(
                LocalDateTime.of(2026, 10, 3, 12, 0)
        );

        return review;
    }

    private DataIntegrityViolationException constraintViolation(String constraintName, int errorCode) {
        SQLException sqlException = new SQLException("리뷰 저장 제약 위반", "23000", errorCode);

        ConstraintViolationException violation =
                new ConstraintViolationException(
                        "리뷰 저장 제약 위반",
                        sqlException,
                        constraintName
                );

        return new DataIntegrityViolationException("리뷰를 저장할 수 없습니다.", violation);
    }
}
