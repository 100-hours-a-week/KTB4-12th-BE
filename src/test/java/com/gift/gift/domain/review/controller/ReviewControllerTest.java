package com.gift.gift.domain.review.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.review.dto.response.ReviewResponse;
import com.gift.gift.domain.review.dto.response.DeleteReviewResponse;
import com.gift.gift.domain.review.exception.ReviewErrorCode;
import com.gift.gift.domain.review.exception.ReviewException;
import com.gift.gift.domain.review.exception.ReviewRequestExceptionHandler;
import com.gift.gift.domain.review.service.ReviewService;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.support.TestValidatorFactory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class ReviewControllerTest {

    private static final Long USER_ID = 20L;
    private static final Long GIFT_ID = 10L;

    private ReviewService reviewService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        reviewService = mock(ReviewService.class);
        validator = TestValidatorFactory.create();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new ReviewController(reviewService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(
                        new ReviewRequestExceptionHandler(),
                        new GlobalExceptionHandler()
                )
                .setValidator(validator)
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder
                .getContext()
                .setAuthentication(
                        new JwtAuthenticationToken(
                                jwt,
                                List.of(),
                                USER_ID.toString()
                        )
                );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    @Test
    @DisplayName("정상 요청은 201과 생성된 리뷰를 반환한다")
    void createReview_returnsCreatedReview() throws Exception {
        when(reviewService.createReview(any(), any(), any()))
                .thenReturn(response());

        mockMvc.perform(post(
                        "/gifts/{giftId}/review",
                        GIFT_ID
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "rating": 5,
                                  "content": "좋아요"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("리뷰를 등록했습니다."))
                .andExpect(jsonPath("$.data.review.reviewId").value(71))
                .andExpect(jsonPath("$.data.review.giftId").value(10))
                .andExpect(jsonPath("$.data.review.rating").value(5))
                .andExpect(jsonPath("$.data.review.content").value("좋아요"))
                .andExpect(jsonPath("$.data.review.createdAt").value("2026-10-03T12:00:00"))
                .andExpect(jsonPath("$.data.review.updatedAt").value("2026-10-03T12:00:00"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(reviewService).createReview(eq(GIFT_ID), eq(USER_ID), any());
    }

    @Test
    @DisplayName("리뷰 내용이 누락되어도 별점만으로 등록할 수 있다")
    void createReview_allowsMissingContent() throws Exception {
        when(reviewService.createReview(any(), any(), any()))
                .thenReturn(response());

        mockMvc.perform(post(
                        "/gifts/{giftId}/review",
                        GIFT_ID
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 5
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("리뷰를 등록했습니다."));

        verify(reviewService).createReview(eq(GIFT_ID), eq(USER_ID), any());
    }

    @Test
    @DisplayName("리뷰를 작성할 선물이 없으면 404를 반환한다")
    void createReview_returnsGiftNotFound() throws Exception {
        when(reviewService.createReview(any(), any(), any()))
                .thenThrow(new ReviewException(
                        ReviewErrorCode
                                .REVIEW_CREATE_GIFT_NOT_FOUND
                ));

        mockMvc.perform(post(
                        "/gifts/{giftId}/review",
                        GIFT_ID
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "rating": 5,
                                  "content": "좋아요"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message")
                        .value(
                                "리뷰를 작성할 선물을 찾을 수 없습니다."
                        ))
                .andExpect(jsonPath("$.error.code")
                        .value("GIFT_NOT_FOUND"));
    }

    @Test
    @DisplayName("이미 활성 리뷰가 있으면 409를 반환한다")
    void createReview_returnsConflictForDuplicate()
            throws Exception {
        when(reviewService.createReview(any(), any(), any()))
                .thenThrow(new ReviewException(
                        ReviewErrorCode.REVIEW_ALREADY_EXISTS
                ));

        mockMvc.perform(post(
                        "/gifts/{giftId}/review",
                        GIFT_ID
                )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .content("""
                                {
                                  "rating": 5,
                                  "content": "좋아요"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value(
                                "이미 리뷰를 등록한 선물입니다."
                        ))
                .andExpect(jsonPath("$.error.code")
                        .value("REVIEW_ALREADY_EXISTS"));
    }

    @Test
    @DisplayName("활성 리뷰 조회 요청은 200과 리뷰를 반환한다")
    void getReview_returnsActiveReview() throws Exception {
        when(reviewService.getReview(GIFT_ID, USER_ID))
                .thenReturn(response());

        mockMvc.perform(get("/gifts/{giftId}/review", GIFT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰를 조회했습니다."))
                .andExpect(jsonPath("$.data.review.reviewId").value(71))
                .andExpect(jsonPath("$.data.review.giftId").value(10))
                .andExpect(jsonPath("$.data.review.rating").value(5))
                .andExpect(jsonPath("$.data.review.content").value("좋아요"))
                .andExpect(jsonPath("$.data.review.createdAt").value("2026-10-03T12:00:00"))
                .andExpect(jsonPath("$.data.review.updatedAt").value("2026-10-03T12:00:00"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(reviewService).getReview(GIFT_ID, USER_ID);
    }

    @Test
    @DisplayName("선물 ID가 양수가 아니면 400을 반환한다")
    void getReview_rejectsNonPositiveGiftId() throws Exception {
        mockMvc.perform(
                        get("/gifts/{giftId}/review", 0)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("입력값을 확인해 주세요."))
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(reviewService);
    }

    @Test
    @DisplayName("리뷰 수정 요청은 200과 수정된 리뷰를 반환한다")
    void updateReview_returnsUpdatedReview() throws Exception {
        when(reviewService.updateReview(any(), any(), any()))
                .thenReturn(response());

        mockMvc.perform(patch("/gifts/{giftId}/review", GIFT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 4,
                                  "content": "수정한 리뷰"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰를 수정했습니다."))
                .andExpect(jsonPath("$.data.review.reviewId").value(71));

        verify(reviewService).updateReview(eq(GIFT_ID), eq(USER_ID), any());
    }

    @Test
    @DisplayName("리뷰 수정에서 null 본문을 허용한다")
    void updateReview_allowsNullContent() throws Exception {
        when(reviewService.updateReview(any(), any(), any()))
                .thenReturn(response());

        mockMvc.perform(patch("/gifts/{giftId}/review", GIFT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "rating": 4,
                                  "content": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰를 수정했습니다."));

        verify(reviewService).updateReview(eq(GIFT_ID), eq(USER_ID), any());
    }

    @Test
    @DisplayName("리뷰 삭제 요청은 200과 작성 전 상태를 반환한다")
    void deleteReview_returnsNotWrittenStatus() throws Exception {
        when(reviewService.deleteReview(GIFT_ID, USER_ID))
                .thenReturn(DeleteReviewResponse.from(GIFT_ID));

        mockMvc.perform(delete("/gifts/{giftId}/review", GIFT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("리뷰를 삭제했습니다."))
                .andExpect(jsonPath("$.data.giftId").value(10))
                .andExpect(jsonPath("$.data.reviewStatus").value("NOT_WRITTEN"));

        verify(reviewService).deleteReview(GIFT_ID, USER_ID);
    }

    private ReviewResponse response() {
        return new ReviewResponse(
                new ReviewResponse.ReviewDetail(
                        71L,
                        GIFT_ID,
                        5,
                        "좋아요",
                        LocalDateTime.of(2026, 10, 3, 12, 0),
                        LocalDateTime.of(2026, 10, 3, 12, 0)
                )
        );
    }
}
