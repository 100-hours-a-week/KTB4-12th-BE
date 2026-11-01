package com.gift.gift.domain.recommendation.profiling.event;

public record RecommendationAnalysisCompletedEvent(
        Long recipientId,
        long analyzedSourceVersion
) {
}
