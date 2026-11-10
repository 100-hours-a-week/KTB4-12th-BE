package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingJob;
import com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobRepository;
import com.gift.gift.domain.recommendation.profiling.repository.ProfilingJobRepository.BlockedJobView;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.ProfileRequestFactory;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingMessageSerializer;
import com.gift.gift.domain.user.service.UserQueryService;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilingRecoveryService {

    private final RecipientProfileRepository profiles;
    private final ProfilingJobRepository jobs;
    private final UserQueryService users;
    private final ProfileRequestFactory requestFactory;
    private final ProfilingMessageSerializer serializer;
    private final Clock clock;

    @Transactional(readOnly = true)
    public List<BlockedJobView> listBlocked(int page) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "페이지는 0 이상이어야 합니다."
            );
        }

        return jobs.findBlockedJobs(PageRequest.of(page, 100));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RecoveryResult recover(Command command) {
        Objects.requireNonNull(command, "복구 명령은 필수입니다.");

        Long recipientId = profiles
                .findRecipientIdByProfileId(command.profileId())
                .orElseThrow(() ->
                        new IllegalStateException("프로필이 없습니다."));

        // 입력 변경·작업 준비와 동일한 사용자 → 프로필 → 작업 순서.
        users.findActiveUserForUpdate(recipientId)
                .orElseThrow(() ->
                        new IllegalStateException("활성 사용자가 아닙니다."));

        RecipientProfile profile = profiles
                .findByIdForUpdate(command.profileId())
                .orElseThrow(() ->
                        new IllegalStateException("프로필이 없습니다."));

        if (profile.getSourceVersion() != command.sourceVersion()) {
            throw new IllegalStateException(
                    "현재 프로필 버전이 달라졌습니다. 다시 조회하세요."
            );
        }

        if (profile.getProfileStatus() != RecipientProfileStatus.PENDING
                || profile.getPendingSince() == null
                || profile.getAnalyzedSourceVersion()
                >= command.sourceVersion()) {
            throw new IllegalStateException(
                    "이미 결과가 반영됐거나 복구 가능한 상태가 아닙니다."
            );
        }

        ProfilingJob previous = jobs.findByProfileVersionForUpdate(
                profile.getId(),
                command.sourceVersion()
        ).orElseThrow(() ->
                new IllegalStateException("현재 버전의 작업이 없습니다."));

        if (!previous.getRequestId().equals(command.requestId())
                || !Objects.equals(
                previous.getUpdatedAt(),
                command.expectedUpdatedAt()
        )) {
            throw new IllegalStateException(
                    "조회 이후 작업이 변경됐습니다. 다시 조회하세요."
            );
        }

        previous.requireManualRecovery();

        // 예산 초기화 전에 감사 로그에 필요한 값만 캡처한다.
        int previousAttempts = previous.getAttemptCount();
        LocalDateTime previousFirstFailedAt =
                previous.getFirstFailedAt();
        String previousErrorCode = previous.getLastErrorCode();

        LocalDateTime now = LocalDateTime.now(clock);
        ProfilingJob resumed;

        if (command.action() == Action.RESUME_SAME) {
            previous.resumeBlockedPublication();

            // 그동안 발생한 입력 변경 대기는 보존한다.
            profile.markPending(now);
            resumed = previous;
        } else {
            long nextVersion = profile.createNextSourceVersion();

            var payload = requestFactory.create(profile, nextVersion);
            var message = ProfilingRequestMessage.create(
                    UUID.randomUUID(),
                    clock.instant(),
                    payload
            );

            resumed = ProfilingJob.create(
                    profile,
                    message,
                    serializer
            );

            if (resumed.getPublishStatus()
                    != ProfilingPublishStatus.READY) {
                throw new IllegalStateException(
                        "최신 입력도 메시지 크기 제한을 초과했습니다."
                );
            }

            previous.supersedeBlocked(now);
            jobs.saveAndFlush(resumed);

            profile.clearPendingChange();
            profile.markPending(now);
        }

        jobs.flush();

        RecoveryResult result = new RecoveryResult(
                command.action(),
                profile.getId(),
                previous.getRequestId(),
                resumed.getRequestId(),
                previous.getSourceVersion(),
                resumed.getSourceVersion()
        );

        registerCommittedAudit(
                command,
                result,
                previousAttempts,
                previousFirstFailedAt,
                previousErrorCode
        );

        return result;
    }

    private void registerCommittedAudit(
            Command command,
            RecoveryResult result,
            int previousAttempts,
            LocalDateTime previousFirstFailedAt,
            String previousErrorCode
    ) {
        String safeErrorCode = previousErrorCode == null
                ? "-"
                : previousErrorCode.replaceAll("\\p{Cntrl}", " ");

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        log.info(
                                "PROFILING_RECOVERY_COMMITTED "
                                        + "action={} operator={} "
                                        + "profileId={} "
                                        + "previousRequestId={} "
                                        + "nextRequestId={} "
                                        + "previousVersion={} "
                                        + "nextVersion={} "
                                        + "previousAttempts={} "
                                        + "previousFirstFailedAt={} "
                                        + "previousErrorCode={} "
                                        + "reason={}",
                                result.action(),
                                command.operator(),
                                result.profileId(),
                                result.previousRequestId(),
                                result.nextRequestId(),
                                result.previousSourceVersion(),
                                result.nextSourceVersion(),
                                previousAttempts,
                                previousFirstFailedAt,
                                safeErrorCode,
                                command.reason()
                        );
                    }
                }
        );
    }

    public enum Action {
        RESUME_SAME,
        REPLACE_LATEST
    }

    public record Command(
            Action action,
            Long profileId,
            String requestId,
            long sourceVersion,
            LocalDateTime expectedUpdatedAt,
            String operator,
            String reason
    ) {
        public Command {
            Objects.requireNonNull(action, "복구 방식은 필수입니다.");
            Objects.requireNonNull(profileId, "프로필 ID는 필수입니다.");
            Objects.requireNonNull(requestId, "요청 ID는 필수입니다.");
            Objects.requireNonNull(
                    expectedUpdatedAt,
                    "조회 당시 수정 시각은 필수입니다."
            );
            Objects.requireNonNull(operator, "작업자는 필수입니다.");
            Objects.requireNonNull(reason, "사유는 필수입니다.");

            if (profileId <= 0 || sourceVersion < 1) {
                throw new IllegalArgumentException(
                        "프로필 ID와 버전은 양수여야 합니다."
                );
            }

            if (!UUID.fromString(requestId).toString()
                    .equals(requestId)) {
                throw new IllegalArgumentException(
                        "요청 ID 형식이 올바르지 않습니다."
                );
            }

            operator = operator.trim();
            reason = reason.trim();

            if (!operator.matches("[A-Za-z0-9._-]{1,100}")) {
                throw new IllegalArgumentException(
                        "작업자는 영문·숫자·점·밑줄·하이픈으로 입력하세요."
                );
            }

            if (reason.isEmpty()
                    || reason.length() > 500
                    || reason.chars()
                    .anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "사유는 제어문자 없는 1~500자로 입력하세요."
                );
            }
        }
    }

    public record RecoveryResult(
            Action action,
            Long profileId,
            String previousRequestId,
            String nextRequestId,
            long previousSourceVersion,
            long nextSourceVersion
    ) {
    }
}
