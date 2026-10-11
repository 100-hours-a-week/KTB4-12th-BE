package com.gift.gift.domain.recommendation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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

        LocalDateTime claimNow =
                recipientProfileRepository.findDispatchClaimNow();

        if (profile.hasActiveDispatchClaim(
                claimNow,
                properties.dispatchClaimTimeout()
        )) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        boolean reuseSourceVersion = false;

        String previousToken = profile.getDispatchClaimToken();

        if (previousToken != null) {
            // 해제 전에 기존 선점의 변경 스냅샷을 확인해야 한다.
            LocalDateTime previousSnapshot =
                    profile.getDispatchClaimLastChangedAt();

            boolean currentVersionCompleted =
                    profile.getSourceVersion() > 0
                            && profile.getAnalyzedSourceVersion()
                            >= profile.getSourceVersion();

            if (previousSnapshot != null) {
                boolean sameChange = Objects.equals(
                        profile.getLastChangedAt(),
                        previousSnapshot
                );

                if (currentVersionCompleted) {
                    if (sameChange) {
                        profile.clearPendingChange();
                        profile.resetRetryCount();
                    }
                } else if (sameChange && profile.getSourceVersion() > 0) {
                    // 접수 여부가 불확실한 같은 변경은 기존 번호로 재전송한다.
                    reuseSourceVersion = true;
                }
            }

            profile.releaseDispatchClaim(previousToken);
        }

        if (!reuseSourceVersion && !profile.isDispatchDue(
                now,
                properties.quietPeriod(),
                properties.maximumWindow()
        )) {
            return Optional.empty();
        }

        LocalDateTime snapshottedLastChangedAt =
                profile.getLastChangedAt();

        long sourceVersion = reuseSourceVersion
                ? profile.getSourceVersion()
                : profile.createNextSourceVersion();

        String claimToken = UUID.randomUUID().toString();

        profile.claimDispatch(
                claimToken,
                claimNow,
                snapshottedLastChangedAt
        );

        return Optional.of(new PreparedProfileDispatch(
                profile.getId(),
                createRequest(profile, sourceVersion),
                snapshottedLastChangedAt,
                claimToken
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

        if (!profile.matchesDispatchClaim(dispatch.claimToken())) {
            return;
        }

        try {
            if (profile.getSourceVersion() != dispatch.sourceVersion()
                    || !Objects.equals(
                    profile.getDispatchClaimLastChangedAt(),
                    dispatch.snapshottedLastChangedAt()
            )) {
                return;
            }

            if (response.sourceVersion() != dispatch.sourceVersion()) {
                throw new IllegalStateException(
                        "AI 응답 번호가 준비한 요청 번호와 일치하지 않습니다."
                );
            }

            profile.applyAcceptedResponse(
                    dispatch.sourceVersion(),
                    dispatch.snapshottedLastChangedAt(),
                    LocalDateTime.now(clock)
            );
        } finally {
            profile.releaseDispatchClaim(dispatch.claimToken());
        }
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

        if (!profile.matchesDispatchClaim(dispatch.claimToken())) {
            return FailedDispatchResult.SKIPPED;
        }

        try {
            if (profile.getSourceVersion() != dispatch.sourceVersion()
                    || !Objects.equals(
                    profile.getDispatchClaimLastChangedAt(),
                    dispatch.snapshottedLastChangedAt()
            )) {
                return FailedDispatchResult.SKIPPED;
            }

            // 완료 콜백이 먼저 도착했다면 재시작하지 않는다.
            if (profile.getAnalyzedSourceVersion()
                    >= dispatch.sourceVersion()) {
                profile.applyAcceptedResponse(
                        dispatch.sourceVersion(),
                        dispatch.snapshottedLastChangedAt(),
                        LocalDateTime.now(clock)
                );

                return FailedDispatchResult.SKIPPED;
            }

            // 이전 요청의 실패로 신규 변경을 재시작하거나 지우지 않는다.
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
        } finally {
            profile.releaseDispatchClaim(dispatch.claimToken());
        }
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

        LocalDateTime claimNow = recipientProfileRepository.findDispatchClaimNow();

        if (profile.hasActiveDispatchClaim(
                claimNow,
                properties.dispatchClaimTimeout()
        )) {
            return Optional.empty();
        }

        String previousToken = profile.getDispatchClaimToken();

        if (previousToken != null) {
            // 일반 선점의 회수는 변경 스냅샷을 판단하는 일반 경로가 담당한다.
            if (profile.getDispatchClaimLastChangedAt() != null) {
                return Optional.empty();
            }

            profile.releaseDispatchClaim(previousToken);
        }

        LocalDateTime pendingCutoff = LocalDateTime.now(clock)
                .minus(properties.maximumWindow());

        if (profile.getProfileStatus() != RecipientProfileStatus.PENDING
                || profile.getPendingSince() == null
                || profile.getPendingSince().isAfter(pendingCutoff)
                || profile.getLastChangedAt() != null
                || profile.getSourceVersion() <= 0
                || profile.getAnalyzedSourceVersion()
                >= profile.getSourceVersion()) {
            return Optional.empty();
        }

        String claimToken = UUID.randomUUID().toString();

        profile.claimDispatch(
                claimToken,
                claimNow,
                null
        );

        return Optional.of(new PreparedRecoveryDispatch(
                profile.getId(),
                createRequest(profile, profile.getSourceVersion()),
                profile.getPendingSince(),
                claimToken
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

        if (!profile.matchesDispatchClaim(dispatch.claimToken())) {
            return RecoveryDispatchResult.SKIPPED;
        }

        try {
            if (profile.getDispatchClaimLastChangedAt() != null
                    || profile.getSourceVersion() != dispatch.sourceVersion()
                    || profile.getAnalyzedSourceVersion()
                    >= dispatch.sourceVersion()
                    || profile.getProfileStatus()
                    != RecipientProfileStatus.PENDING
                    || profile.getLastChangedAt() != null
                    || !Objects.equals(
                    profile.getPendingSince(),
                    dispatch.snapshottedPendingSince()
            )) {
                return RecoveryDispatchResult.SKIPPED;
            }

            profile.markPending(LocalDateTime.now(clock));

            return RecoveryDispatchResult.WAIT_RESTARTED;
        } finally {
            profile.releaseDispatchClaim(dispatch.claimToken());
        }
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
