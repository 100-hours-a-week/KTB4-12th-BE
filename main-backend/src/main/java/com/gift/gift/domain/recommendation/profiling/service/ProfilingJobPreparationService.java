package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.profiling.dto.request.AiProfileRequest;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingJob;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobRepository;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.profiling.support.ProfileRequestFactory;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingMessageSerializer;
import com.gift.gift.domain.user.service.UserQueryService;

@Service
@RequiredArgsConstructor
public class ProfilingJobPreparationService {

    private final RecipientProfileRepository profileRepository;
    private final ProfilingJobRepository jobRepository;
    private final UserQueryService userQueryService;
    private final ProfileRequestFactory requestFactory;
    private final ProfilingMessageSerializer serializer;
    private final AiProfilingProperties properties;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Optional<String> prepare(Long profileId) {
        Long recipientId = profileRepository
                .findRecipientIdByProfileId(profileId)
                .orElse(null);

        if (recipientId == null) {
            return Optional.empty();
        }

        // 입력 변경 경로와 동일하게 사용자 → 프로필 순서로 잠근다.
        if (userQueryService.findActiveUserForUpdate(recipientId).isEmpty()) {
            return Optional.empty();
        }

        RecipientProfile profile = profileRepository
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

        ProfilingJob previous = jobRepository
                .findByProfileVersionForUpdate(
                        profileId,
                        profile.getSourceVersion()
                )
                .orElse(null);

        if (previous != null && previous.isUnconfirmedPublication()) {
            return Optional.empty();
        }

        long sourceVersion = profile.createNextSourceVersion();

        AiProfileRequest payload =
                requestFactory.create(profile, sourceVersion);

        ProfilingRequestMessage message =
                ProfilingRequestMessage.create(
                        UUID.randomUUID(),
                        clock.instant(),
                        payload
                );

        ProfilingJob job = ProfilingJob.create(
                profile,
                message,
                serializer
        );

        if (previous != null) {
            previous.supersedeBlocked(now);
        }

        jobRepository.saveAndFlush(job);

        profile.clearPendingChange();
        profile.markPending(now);

        return Optional.of(job.getRequestId());
    }
}
