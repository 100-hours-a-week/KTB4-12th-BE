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
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.support.PreparedProfileDispatch;

@Service
@RequiredArgsConstructor
public class ProfileDispatchTransactionService {

    private final RecipientProfileRepository recipientProfileRepository;
    private final UserDislikeCategoryRepository dislikeRepository;
    private final AiProfilingProperties properties;
    private final Clock clock;

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
        Long recipientUserId = profile.getRecipient().getId();

        List<DislikedCategoryRequest> dislikedCategories =
                dislikeRepository
                        .findAllActiveByUserIdWithCategory(recipientUserId)
                        .stream()
                        .map(UserDislikeCategory::getCategory)
                        .map(DislikedCategoryRequest::from)
                        .toList();

        AiProfileRequest request = new AiProfileRequest(
                recipientUserId,
                sourceVersion,
                dislikedCategories
        );

        return Optional.of(new PreparedProfileDispatch(
                profile.getId(),
                request,
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

    public enum FailedDispatchResult {
        DEBOUNCE_RESTARTED,
        ABANDONED,
        SKIPPED
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

        // 현재 번호와 변경 스냅샷이 모두 일치해야 실패를 반영한다.
        if (profile.getSourceVersion() != dispatch.sourceVersion()
                || !Objects.equals(
                profile.getLastChangedAt(),
                dispatch.snapshottedLastChangedAt()
        )) {
            return FailedDispatchResult.SKIPPED;
        }

        // 일반 요청 실패의 디바운스 재시작은 1회만 허용한다.
        if (profile.getRetryCount() == 0) {
            profile.restartDebounce(LocalDateTime.now(clock));
            profile.increaseRetryCount();
            return FailedDispatchResult.DEBOUNCE_RESTARTED;
        }

        profile.clearPendingChange();
        profile.resetRetryCount();
        return FailedDispatchResult.ABANDONED;
    }
}
