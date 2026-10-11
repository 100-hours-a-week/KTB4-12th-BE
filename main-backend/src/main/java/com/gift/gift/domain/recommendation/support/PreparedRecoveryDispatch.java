package com.gift.gift.domain.recommendation.support;

import java.time.LocalDateTime;
import java.util.Objects;

import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;

public record PreparedRecoveryDispatch(
        Long profileId,
        AiProfileRequest request,
        LocalDateTime snapshottedPendingSince,
        String claimToken
) {

    public PreparedRecoveryDispatch {
        Objects.requireNonNull(
                profileId,
                "프로파일 ID는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                request,
                "AI 복구 요청은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                snapshottedPendingSince,
                "복구 당시 PENDING 시각은 null일 수 없습니다."
        );

        if (request.sourceVersion() < 1) {
            throw new IllegalArgumentException(
                    "복구 요청 번호는 1 이상이어야 합니다."
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
