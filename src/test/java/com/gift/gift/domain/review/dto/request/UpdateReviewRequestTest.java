package com.gift.gift.domain.review.dto.request;

import java.util.Set;

import jakarta.validation.ConstraintViolation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.support.TestValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateReviewRequestTest {

    private static LocalValidatorFactoryBean validator;

    @BeforeAll
    static void setUpValidator() {
        validator = TestValidatorFactory.create();
    }

    @AfterAll
    static void closeValidator() {
        validator.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5})
    @DisplayName("수정 별점은 1부터 5까지 허용한다")
    void ratingBoundary_isValid(int rating) {
        UpdateReviewRequest request =
                new UpdateReviewRequest(rating, "좋아요");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("수정 별점이 null이면 기존 별점을 유지할 수 있다")
    void nullRating_isValid() {
        UpdateReviewRequest request =
                new UpdateReviewRequest(null, "좋아요");

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 6})
    @DisplayName("수정 별점이 범위를 벗어나면 OUT_OF_RANGE로 거부한다")
    void outOfRangeRating_isInvalid(int rating) {
        UpdateReviewRequest request =
                new UpdateReviewRequest(rating, "좋아요");

        assertViolation(request, "rating", "OUT_OF_RANGE");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("수정 리뷰 내용이 null이거나 빈 문자열이면 허용한다")
    void emptyContent_isValid(String content) {
        UpdateReviewRequest request =
                new UpdateReviewRequest(4, content);

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\t", "\n", " \t\n "})
    @DisplayName("수정 리뷰 내용이 공백 전용 문자열이면 허용한다")
    void whitespaceOnlyContent_isValid(String content) {
        UpdateReviewRequest request =
                new UpdateReviewRequest(4, content);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("수정 리뷰 내용이 유니코드 코드 포인트 300자를 넘으면 거부한다")
    void contentOver300CodePoints_isInvalid() {
        UpdateReviewRequest request =
                new UpdateReviewRequest(
                        4,
                        "😊".repeat(301)
                );

        assertViolation(
                request,
                "content",
                "MAX_LENGTH_EXCEEDED"
        );
    }

    private void assertViolation(
            UpdateReviewRequest request,
            String field,
            String reason
    ) {
        Set<ConstraintViolation<UpdateReviewRequest>> violations =
                validator.validate(request);

        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath().toString())
                    .isEqualTo(field);
            assertThat(violation.getMessage())
                    .isEqualTo(reason);
        });
    }
}
