package com.gift.gift.domain.recommendation.profiling.dto.request;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class AiProfileRequestTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    @DisplayName("입력이 없어도 다섯 키와 null 취향을 전송한다")
    void serialization_includesEmptyInputs() {
        var json = mapper.readTree(mapper.writeValueAsString(
                new AiProfileRequest(1L, 1, List.of(), null, List.of())
        ));

        assertThat(json.size()).isEqualTo(5);
        assertThat(json.get("giftPreference").isNull()).isTrue();
        assertThat(json.get("reviews").isArray()).isTrue();
        assertThat(json.get("reviews").size()).isZero();
        assertThat(json.get("dislikedCategories").isArray()).isTrue();
    }

    @Test
    @DisplayName("공백 리뷰는 null 키를 유지하고 나머지 원문은 보존한다")
    void serialization_normalizesOnlyBlankReviewText() {
        var blank = mapper.readTree(mapper.writeValueAsString(
                new ProfileReviewRequest(1L, 5, " \n\t")
        ));

        assertThat(blank.get("reviewText").isNull()).isTrue();
        assertThat(new ProfileReviewRequest(2L, 2, " 배송은 늦지만 상품은 좋아요. ").reviewText())
                .isEqualTo(" 배송은 늦지만 상품은 좋아요. ");
    }
}
