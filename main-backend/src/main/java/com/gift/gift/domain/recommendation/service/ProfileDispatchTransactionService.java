package com.gift.gift.domain.recommendation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.preference.entity.UserDislikeCategory;
import com.gift.gift.domain.preference.repository.UserDislikeCategoryRepository;
import com.gift.gift.domain.recommendation.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.dto.request.DislikedCategoryRequest;
import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.support.PreparedProfileDispatch;
import com.gift.gift.domain.recommendation.support.PreparedRecoveryDispatch;

@Service
@RequiredArgsConstructor
public class ProfileDispatchTransactionService {

    private final RecipientProfileRepository recipientProfileRepository;
    private final UserDislikeCategoryRepository dislikeRepository;
    private final AiProfilingProperties properties;
    private final Clock clock;

    public enum FailedDispatchResult {
        DEBOUNCE_RESTARTED,
        ABANDONED,
        SKIPPED
    }

    public enum RecoveryDispatchResult {
        WAIT_RESTARTED,
        SKIPPED
    }

    @Transactional
    public Optional<PreparedProfileDispatch> prepareDispatch(
            Long profileId
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByIdForUpdate(profileId)
                .orElse(null);

        if (profile == null) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(clock);

        if (!profile.isDispatchDue(
                now,
                properties.quietPeriod(),
                properties.maximumWindow()
        )) {
            return Optional.empty();
        }

        LocalDateTime snapshottedLastChangedAt =
                profile.getLastChangedAt();

        long sourceVersion = profile.createNextSourceVersion();

        return Optional.of(new PreparedProfileDispatch(
                profile.getId(),
                createRequest(profile, sourceVersion),
                snapshottedLastChangedAt
        ));
    }

    @Transactional
    public void applyAcceptedResponse(
            PreparedProfileDispatch dispatch,
            AiProfileAcceptedResponse response
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByIdForUpdate(dispatch.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "AI 요청을 반영할 프로파일이 없습니다."
                ));

        profile.applyAcceptedResponse(
                response.sourceVersion(),
                dispatch.snapshottedLastChangedAt(),
                LocalDateTime.now(clock)
        );
    }

    @Transactional
    public FailedDispatchResult applyFailedDispatch(
            PreparedProfileDispatch dispatch
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByIdForUpdate(dispatch.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "AI 요청 실패를 반영할 프로파일이 없습니다."
                ));

        if (profile.getSourceVersion() != dispatch.sourceVersion()) {
            return FailedDispatchResult.SKIPPED;
        }

        // 완료 콜백은 HTTP 실패보다 먼저 도착할 수 있다.
        // 완료된 번호를 다시 전송하도록 예약하지 않는다.
        if (profile.getAnalyzedSourceVersion() >= dispatch.sourceVersion()) {
            profile.applyAcceptedResponse(
                    dispatch.sourceVersion(),
                    dispatch.snapshottedLastChangedAt(),
                    LocalDateTime.now(clock)
            );
            return FailedDispatchResult.SKIPPED;
        }

        if (!Objects.equals(
                profile.getLastChangedAt(),
                dispatch.snapshottedLastChangedAt()
        )) {
            return FailedDispatchResult.SKIPPED;
        }

        if (profile.getRetryCount() == 0) {
            profile.restartDebounce(LocalDateTime.now(clock));
            profile.increaseRetryCount();
            return FailedDispatchResult.DEBOUNCE_RESTARTED;
        }

        profile.clearPendingChange();
        profile.resetRetryCount();
        return FailedDispatchResult.ABANDONED;
    }

    @Transactional
    public Optional<PreparedRecoveryDispatch> prepareRecovery(
            Long profileId
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByIdForUpdate(profileId)
                .orElse(null);

        if (profile == null) {
            return Optional.empty();
        }

        LocalDateTime pendingCutoff = LocalDateTime.now(clock)
                .minus(properties.maximumWindow());

        if (profile.getProfileStatus() != RecipientProfileStatus.PENDING
                || profile.getPendingSince() == null
                || profile.getPendingSince().isAfter(pendingCutoff)
                || profile.getLastChangedAt() != null) {
            return Optional.empty();
        }

        // 복구는 기존 번호로 현재 비선호를 구성한다.
        // 번호와 retry_count는 변경하지 않는다.
        return Optional.of(new PreparedRecoveryDispatch(
                profile.getId(),
                createRequest(profile, profile.getSourceVersion()),
                profile.getPendingSince()
        ));
    }

    @Transactional
    public RecoveryDispatchResult applyRecoveryResult(
            PreparedRecoveryDispatch dispatch
    ) {
        RecipientProfile profile = recipientProfileRepository
                .findByIdForUpdate(dispatch.profileId())
                .orElseThrow(() -> new IllegalStateException(
                        "AI 복구 결과를 반영할 프로파일이 없습니다."
                ));

        if (profile.getSourceVersion() != dispatch.sourceVersion()
                || profile.getProfileStatus() != RecipientProfileStatus.PENDING
                || !Objects.equals(
                profile.getPendingSince(),
                dispatch.snapshottedPendingSince()
        )) {
            return RecoveryDispatchResult.SKIPPED;
        }

        // 번호와 PENDING 상태를 검증한 뒤에만 호출한다.
        // 신규 변경 시각과 retry_count는 그대로 유지된다.
        profile.markPending(LocalDateTime.now(clock));

        return RecoveryDispatchResult.WAIT_RESTARTED;
    }

    private AiProfileRequest createRequest(
            RecipientProfile profile,
            long sourceVersion
    ) {
        Long recipientUserId = profile.getRecipient().getId();

        List<DislikedCategoryRequest> dislikedCategories =
                dislikeRepository
                        .findAllActiveByUserIdWithCategory(recipientUserId)
                        .stream()
                        .map(UserDislikeCategory::getCategory)
                        .map(DislikedCategoryRequest::from)
                        .toList();

        return new AiProfileRequest(
                recipientUserId,
                sourceVersion,
                dislikedCategories
        );
    }
}
