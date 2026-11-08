package com.gift.gift.domain.recommendation.profiling.dto.request;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Data;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Error;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Payload;

import static org.assertj.core.api.Assertions.*;

class ProfilingResultMessageTest {

    @Test
    @DisplayName("입력 목록을 나중에 변경해도 검증된 추천 순서와 해시 입력은 바뀌지 않는다")
    void copiesAndProtectsProductIds() {
        var productIds = new ArrayList<>(List.of(103L, 208L));
        var data = new Data(productIds);
        productIds.clear();

        assertThat(data.recommendedProductIds()).containsExactly(103L, 208L);
        assertThatThrownBy(() -> data.recommendedProductIds().add(999L))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Reader를 거치지 않은 직접 생성도 성공·실패 데이터 혼용을 거부한다")
    void rejectsInvalidOutcomeDataCombinations() {
        var data = new Data(List.of());
        var error = new Error("ANALYSIS_FAILED", "분석 실패");

        assertThatThrownBy(() -> new Payload(1, 1, Outcome.SUCCEEDED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payload(1, 1, Outcome.SUCCEEDED, data, error))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payload(1, 1, Outcome.FAILED, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Payload(1, 1, Outcome.FAILED, data, error))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
