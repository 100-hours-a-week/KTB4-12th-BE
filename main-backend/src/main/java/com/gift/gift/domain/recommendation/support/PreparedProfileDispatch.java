package com.gift.gift.domain.recommendation.support;

import java.time.LocalDateTime;
import java.util.Objects;

import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;

public record PreparedProfileDispatch(
        Long profileId,
        AiProfileRequest request,
        LocalDateTime snapshottedLastChangedAt,
        String claimToken
) {

    public PreparedProfileDispatch {
        Objects.requireNonNull(
                profileId,
                "프로파일 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                request,
                "AI 프로파일 요청은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                snapshottedLastChangedAt,
                "스냅샷 변경 시각은 null일 수 없습니다."
        );
        if (claimToken == null || claimToken.isBlank()) {
            throw new IllegalArgumentException(
                    "선점 토큰은 null이거나 공백일 수 없습니다."
            );
        }
    }

    public long sourceVersion() {
        return request.sourceVersion();
    }

    public Long recipientUserId() {
        return request.recipientUserId();
    }
}
