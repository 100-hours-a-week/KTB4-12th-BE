package com.gift.gift.domain.review.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.review.dto.request.CreateReviewRequest;
import com.gift.gift.domain.review.dto.request.UpdateReviewRequest;
import com.gift.gift.domain.review.dto.response.DeleteReviewResponse;
import com.gift.gift.domain.review.dto.response.ReviewResponse;
import com.gift.gift.domain.review.response.ReviewSuccessCode;
import com.gift.gift.domain.review.service.ReviewService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/gifts")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/{giftId}/review")
    public ResponseEntity<ApiResponse<ReviewResponse>> createReview(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId,
            @Valid @RequestBody CreateReviewRequest request
    ) {
        ReviewResponse response = reviewService.createReview(
                giftId,
                userId,
                request
        );
        ReviewSuccessCode successCode = ReviewSuccessCode.REVIEW_CREATED;

        return ResponseEntity
                .status(successCode.status())
                .body(
                        ApiResponse.success(successCode.message(), response)
                );
    }

    @GetMapping("/{giftId}/review")
    public ResponseEntity<ApiResponse<ReviewResponse>> getReview(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId
    ) {
        ReviewResponse response = reviewService.getReview(giftId, userId);
        ReviewSuccessCode successCode = ReviewSuccessCode.REVIEW_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(
                        ApiResponse.success(successCode.message(), response)
                );
    }

    @PatchMapping("/{giftId}/review")
    public ResponseEntity<ApiResponse<ReviewResponse>> updateReview(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId,
            @Valid @RequestBody UpdateReviewRequest request
    ) {
        ReviewResponse response = reviewService.updateReview(
                giftId,
                userId,
                request
        );
        ReviewSuccessCode successCode = ReviewSuccessCode.REVIEW_UPDATED;

        return ResponseEntity
                .status(successCode.status())
                .body(
                        ApiResponse.success(successCode.message(), response)
                );
    }

    @DeleteMapping("/{giftId}/review")
    public ResponseEntity<ApiResponse<DeleteReviewResponse>> deleteReview(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId
    ) {
        DeleteReviewResponse response = reviewService.deleteReview(
                giftId,
                userId
        );
        ReviewSuccessCode successCode = ReviewSuccessCode.REVIEW_DELETED;

        return ResponseEntity
                .status(successCode.status())
                .body(
                        ApiResponse.success(successCode.message(), response)
                );
    }
}
