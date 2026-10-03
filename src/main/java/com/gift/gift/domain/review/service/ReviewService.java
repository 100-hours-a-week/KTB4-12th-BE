package com.gift.gift.domain.review.service;

import java.sql.SQLException;

import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.review.dto.request.CreateReviewRequest;
import com.gift.gift.domain.review.dto.response.ReviewResponse;
import com.gift.gift.domain.review.entity.Review;
import com.gift.gift.domain.review.exception.ReviewErrorCode;
import com.gift.gift.domain.review.exception.ReviewException;
import com.gift.gift.domain.review.repository.ReviewRepository;

@Service
@RequiredArgsConstructor
public class ReviewService {

    private static final String ACTIVE_REVIEW_UNIQUE_CONSTRAINT = "uk_reviews_active_gift_history";

    private static final int MYSQL_DUPLICATE_KEY_ERROR_CODE = 1062;

    private final GiftHistoryRepository giftHistoryRepository;
    private final ReviewRepository reviewRepository;

    @Transactional
    public ReviewResponse createReview(Long giftId, Long userId, CreateReviewRequest request) {
        try {
            GiftHistory giftHistory = giftHistoryRepository
                    .findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(
                            giftId,
                            userId,
                            GiftStatus.COMPLETED
                    )
                    .orElseThrow(
                            () -> new ReviewException(ReviewErrorCode.REVIEW_CREATE_GIFT_NOT_FOUND)
                    );

            Review review = new Review(
                    giftHistory,
                    giftHistory.getRecipient(),
                    request.rating(),
                    request.content()
            );

            Review savedReview = saveReview(review);

            return ReviewResponse.from(savedReview);
        } catch (ReviewException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ReviewException(ReviewErrorCode.REVIEW_CREATE_FAILED, exception);
        }
    }

    private Review saveReview(Review review) {
        try {
            return reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException exception) {
            if (isActiveReviewUniqueViolation(exception)) {
                throw new ReviewException(ReviewErrorCode.REVIEW_ALREADY_EXISTS);
            }

            throw exception;
        }
    }

    private boolean isActiveReviewUniqueViolation(Throwable exception) {
        Throwable current = exception;

        while (current != null) {
            if (current instanceof
                    ConstraintViolationException violation) {

                String constraintName = normalizeConstraintName(
                        violation.getConstraintName()
                );
                SQLException sqlException =
                        violation.getSQLException();

                return ACTIVE_REVIEW_UNIQUE_CONSTRAINT
                        .equalsIgnoreCase(constraintName)
                        && sqlException != null
                        && sqlException.getErrorCode()
                        == MYSQL_DUPLICATE_KEY_ERROR_CODE;
            }

            current = current.getCause();
        }

        return false;
    }

    private String normalizeConstraintName(String constraintName) {
        if (constraintName == null) {
            return null;
        }

        String normalized = constraintName.replace("`", "");
        int separatorIndex = normalized.lastIndexOf('.');

        if (separatorIndex >= 0) {
            return normalized.substring(separatorIndex + 1);
        }

        return normalized;
    }
}
