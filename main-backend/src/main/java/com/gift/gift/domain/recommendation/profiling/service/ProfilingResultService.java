package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage.Outcome;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingJob;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingResultStatus;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobRepository;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultFingerprint;
import com.gift.gift.domain.user.service.UserQueryService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilingResultService {

    private static final ZoneId HISTORY_ZONE =
            ZoneId.of("Asia/Seoul");

    private final ProfilingJobRepository jobs;
    private final RecipientProfileRepository profiles;
    private final UserQueryService users;
    private final ProfileRecommendationWriter recommendationWriter;
    private final ProfilingResultFingerprint fingerprint;
    private final Clock clock;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public Result save(ProfilingResultMessage message) {
        Objects.requireNonNull(message, "검증된 결과 메시지는 필수입니다.");

        var target = jobs.findResultTarget(message.requestId())
                .orElse(null);

        if (target == null) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.JOB_NOT_FOUND,
                    false,
                    false
            );
        }

        // 입력 변경·작업 준비·수동 복구와 같은 잠금 순서.
        if (users.findActiveUserForUpdate(
                target.getRecipientId()
        ).isEmpty()) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.RECIPIENT_NOT_ACTIVE,
                    false,
                    false
            );
        }

        RecipientProfile profile = profiles
                .findByIdForUpdate(target.getProfileId())
                .orElse(null);

        if (profile == null) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.PROFILE_NOT_FOUND,
                    false,
                    false
            );
        }

        ProfilingJob job = jobs
                .findByRequestIdForUpdate(message.requestId())
                .orElse(null);

        if (job == null) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.JOB_NOT_FOUND,
                    false,
                    false
            );
        }

        var payload = message.payload();

        if (!Objects.equals(
                profile.getRecipient().getId(),
                target.getRecipientId()
        ) || !Objects.equals(
                job.getProfile().getId(),
                profile.getId()
        ) || !Objects.equals(
                profile.getRecipient().getId(),
                payload.recipientUserId()
        ) || job.getSourceVersion() != payload.sourceVersion()) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.IDENTITY_MISMATCH,
                    false,
                    false
            );
        }

        if (payload.sourceVersion() > profile.getSourceVersion()) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.INVALID_VERSION,
                    false,
                    false
            );
        }

        if (job.getResultStatus()
                == ProfilingResultStatus.SUPERSEDED) {
            return conclude(
                    message,
                    Decision.IGNORED,
                    Reason.SUPERSEDED,
                    false,
                    false
            );
        }

        String receivedFingerprint = fingerprint.hash(message);

        ProfilingResultStatus finalStatus =
                payload.outcome() == Outcome.SUCCEEDED
                        ? ProfilingResultStatus.COMPLETED
                        : ProfilingResultStatus.FAILED;

        if (job.getResultStatus() == ProfilingResultStatus.COMPLETED
                || job.getResultStatus() == ProfilingResultStatus.FAILED) {
            if (job.getResultFingerprint() == null) {
                return conclude(
                        message,
                        Decision.QUARANTINE,
                        Reason.TERMINAL_WITHOUT_FINGERPRINT,
                        false,
                        false
                );
            }

            boolean sameResult =
                    job.getResultStatus() == finalStatus
                            && job.getResultFingerprint()
                            .equals(receivedFingerprint);

            return conclude(
                    message,
                    sameResult
                            ? Decision.DUPLICATE
                            : Decision.QUARANTINE,
                    sameResult
                            ? Reason.SAME_RESULT
                            : Reason.CONFLICTING_RESULT,
                    false,
                    false
            );
        }

        if (job.getResultStatus() != ProfilingResultStatus.WAITING
                || job.getResultFingerprint() != null
                || job.getFinishedAt() != null) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.INCONSISTENT_JOB_STATE,
                    false,
                    false
            );
        }

        boolean newerThanStored =
                payload.sourceVersion()
                        > profile.getAnalyzedSourceVersion();

        boolean latestFailure =
                payload.outcome() == Outcome.FAILED
                        && payload.sourceVersion()
                        == profile.getSourceVersion()
                        && newerThanStored;

        if (latestFailure
                && profile.getProfileStatus()
                != RecipientProfileStatus.PENDING) {
            return conclude(
                    message,
                    Decision.QUARANTINE,
                    Reason.INCONSISTENT_PROFILE_STATE,
                    false,
                    false
            );
        }

        boolean recommendationsChanged = false;

        if (payload.outcome() == Outcome.SUCCEEDED
                && newerThanStored) {
            recommendationWriter.replace(
                    profile,
                    payload.sourceVersion(),
                    payload.data().recommendedProductIds()
            );

            recommendationsChanged = true;
        } else if (latestFailure) {
            // 기존 추천·분석 버전·새 입력의 디바운스 정보를 보존한다.
            profile.markFailed();
        }

        Instant processedAt = clock.instant()
                .truncatedTo(ChronoUnit.MICROS);

        LocalDateTime finishedAt = LocalDateTime.ofInstant(
                processedAt,
                HISTORY_ZONE
        );

        LocalDateTime publishedAtFallback = LocalDateTime.ofInstant(
                processedAt,
                ZoneOffset.UTC
        );

        boolean publishedAtInferred = job.finishFromResult(
                finalStatus,
                receivedFingerprint,
                finishedAt,
                publishedAtFallback
        );

        jobs.flush();

        return conclude(
                message,
                Decision.RECORDED,
                Reason.FINAL_RESULT_RECORDED,
                recommendationsChanged,
                publishedAtInferred
        );
    }

    private Result conclude(
            ProfilingResultMessage message,
            Decision decision,
            Reason reason,
            boolean recommendationsChanged,
            boolean publishedAtInferred
    ) {
        Result result = new Result(
                decision,
                reason,
                recommendationsChanged,
                publishedAtInferred
        );

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            return;
                        }

                        log.info(
                                "PROFILING_RESULT_DECISION "
                                        + "requestId={} sourceVersion={} "
                                        + "decision={} reason={} "
                                        + "recommendationsChanged={} "
                                        + "publishedAtInferred={}",
                                message.requestId(),
                                message.payload().sourceVersion(),
                                result.decision(),
                                result.reason(),
                                result.recommendationsChanged(),
                                result.publishedAtInferred()
                        );
                    }
                }
        );

        return result;
    }

    public enum Decision {
        RECORDED,
        DUPLICATE,
        IGNORED,
        QUARANTINE
    }

    public enum Reason {
        FINAL_RESULT_RECORDED,
        SAME_RESULT,
        SUPERSEDED,
        JOB_NOT_FOUND,
        RECIPIENT_NOT_ACTIVE,
        PROFILE_NOT_FOUND,
        IDENTITY_MISMATCH,
        INVALID_VERSION,
        TERMINAL_WITHOUT_FINGERPRINT,
        CONFLICTING_RESULT,
        INCONSISTENT_JOB_STATE,
        INCONSISTENT_PROFILE_STATE
    }

    public record Result(
            Decision decision,
            Reason reason,
            boolean recommendationsChanged,
            boolean publishedAtInferred
    ) {
    }
}
