package com.gift.gift.domain.recommendation.profiling.entity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingRequestMessage;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingMessageSerializer;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@DynamicUpdate
@Table(
        name = "profiling_jobs",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_profiling_jobs_profile_version",
                        columnNames = {"profile_id", "source_version"}
                )
        },
        indexes = {
                @Index(
                        name = "idx_profiling_jobs_publish",
                        columnList =
                                "publish_status, next_attempt_at, created_at"
                ),
                @Index(
                        name = "idx_profiling_jobs_claim",
                        columnList = "publish_status, claimed_at"
                ),
                @Index(
                        name = "idx_profiling_jobs_cleanup",
                        columnList = "result_status, finished_at"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProfilingJob extends BaseTimeEntity {

    @Id
    @Column(name = "request_id", length = 36, updatable = false)
    private String requestId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "profile_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_profiling_jobs_profile"
            )
    )
    private RecipientProfile profile;

    @Column(
            name = "source_version",
            nullable = false,
            updatable = false
    )
    private long sourceVersion;

    @Column(
            name = "request_body",
            nullable = false,
            updatable = false,
            columnDefinition = "LONGTEXT"
    )
    private String requestBody;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "publish_status", nullable = false, length = 20)
    private ProfilingPublishStatus publishStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "first_failed_at")
    private LocalDateTime firstFailedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "claim_token", length = 36)
    private String claimToken;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "result_status", nullable = false, length = 20)
    private ProfilingResultStatus resultStatus;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "last_error_code", length = 100)
    private String lastErrorCode;

    @Column(name = "last_error_message", columnDefinition = "TEXT")
    private String lastErrorMessage;

    @Column(name = "result_fingerprint", columnDefinition = "CHAR(64)")
    private String resultFingerprint;

    public static ProfilingJob create(
            RecipientProfile profile,
            ProfilingRequestMessage message,
            ProfilingMessageSerializer serializer
    ) {
        Objects.requireNonNull(profile, "프로파일은 필수입니다.");
        Objects.requireNonNull(message, "메시지는 필수입니다.");
        Objects.requireNonNull(serializer, "직렬화기는 필수입니다.");

        if (profile.getId() == null) {
            throw new IllegalArgumentException(
                    "저장된 프로파일이 필요합니다."
            );
        }

        if (!Objects.equals(
                profile.getRecipient().getId(),
                message.payload().recipientUserId()
        )) {
            throw new IllegalArgumentException(
                    "프로파일과 메시지의 수신자가 다릅니다."
            );
        }

        if (profile.getSourceVersion()
                != message.payload().sourceVersion()) {
            throw new IllegalArgumentException(
                    "프로파일과 메시지의 요청 버전이 다릅니다."
            );
        }

        ProfilingMessageSerializer.Snapshot snapshot =
                serializer.serialize(message);

        ProfilingJob job = new ProfilingJob();
        job.requestId = message.requestId();
        job.profile = profile;
        job.sourceVersion = message.payload().sourceVersion();
        job.requestBody = snapshot.json();
        job.publishStatus = snapshot.tooLarge()
                ? ProfilingPublishStatus.BLOCKED
                : ProfilingPublishStatus.READY;
        job.resultStatus = ProfilingResultStatus.WAITING;
        job.attemptCount = 0;

        if (snapshot.tooLarge()) {
            job.lastErrorCode = "INPUT_TOO_LARGE";
            job.lastErrorMessage =
                    "프로파일링 메시지가 UTF-8 256 KiB를 초과했습니다.";
        }

        return job;
    }

    public byte[] requestBodyBytes() {
        return requestBody.getBytes(StandardCharsets.UTF_8);
    }

    public boolean isUnconfirmedPublication() {
        return resultStatus == ProfilingResultStatus.WAITING
                && (
                publishStatus == ProfilingPublishStatus.READY
                        || publishStatus == ProfilingPublishStatus.CLAIMED
                        || publishStatus == ProfilingPublishStatus.RETRY_WAIT
        );
    }

    public void supersedeBlocked(LocalDateTime now) {
        Objects.requireNonNull(now, "대체 시각은 필수입니다.");

        if (publishStatus != ProfilingPublishStatus.BLOCKED
                || resultStatus != ProfilingResultStatus.WAITING) {
            return;
        }

        if (claimToken != null || claimedAt != null) {
            throw new IllegalStateException(
                    "선점 중인 작업은 대체할 수 없습니다."
            );
        }

        resultStatus = ProfilingResultStatus.SUPERSEDED;
        finishedAt = now;
        nextAttemptAt = null;
    }

    public void requireManualRecovery() {
        if (publishStatus != ProfilingPublishStatus.BLOCKED
                || resultStatus != ProfilingResultStatus.WAITING) {
            throw new IllegalStateException(
                    "BLOCKED이면서 WAITING인 작업만 복구할 수 있습니다."
            );
        }

        if (claimToken != null || claimedAt != null) {
            throw new IllegalStateException(
                    "선점 정보가 남아 있는 작업은 복구할 수 없습니다."
            );
        }

        if (publishedAt != null || finishedAt != null) {
            throw new IllegalStateException(
                    "발행 확인 또는 종료 기록이 있는 작업은 복구할 수 없습니다."
            );
        }
    }

    public void resumeBlockedPublication() {
        requireManualRecovery();

        if (requestBodyBytes().length
                > ProfilingMessageSerializer.MAX_MESSAGE_BYTES) {
            throw new IllegalStateException(
                    "크기 제한을 초과한 본문은 동일 입력으로 재개할 수 없습니다."
            );
        }

        publishStatus = ProfilingPublishStatus.READY;
        attemptCount = 0;
        firstFailedAt = null;
        nextAttemptAt = null;
        lastErrorCode = null;
        lastErrorMessage = null;
    }

    public boolean finishFromResult(
            ProfilingResultStatus finalStatus,
            String fingerprint,
            LocalDateTime finishedAt,
            LocalDateTime publishedAtFallback
    ) {
        Objects.requireNonNull(finalStatus, "최종 상태는 필수입니다.");
        Objects.requireNonNull(fingerprint, "결과 fingerprint는 필수입니다.");
        Objects.requireNonNull(finishedAt, "종료 시각은 필수입니다.");
        Objects.requireNonNull(
                publishedAtFallback,
                "발행 시각 보완값은 필수입니다."
        );

        if (finalStatus != ProfilingResultStatus.COMPLETED
                && finalStatus != ProfilingResultStatus.FAILED) {
            throw new IllegalArgumentException(
                    "최종 결과는 COMPLETED 또는 FAILED여야 합니다."
            );
        }

        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "결과 fingerprint는 소문자 SHA-256 hex여야 합니다."
            );
        }

        if (resultStatus != ProfilingResultStatus.WAITING
                || resultFingerprint != null
                || this.finishedAt != null) {
            throw new IllegalStateException(
                    "결과를 수락하지 않은 WAITING 작업만 종료할 수 있습니다."
            );
        }

        boolean publishedAtInferred = publishedAt == null;

        resultStatus = finalStatus;
        resultFingerprint = fingerprint;
        this.finishedAt = finishedAt;

        // 유효한 최종 결과는 요청이 실제 전달됐다는 증거다.
        publishStatus = ProfilingPublishStatus.PUBLISHED;

        if (publishedAtInferred) {
            publishedAt = publishedAtFallback;
        }

        claimToken = null;
        claimedAt = null;
        nextAttemptAt = null;

        // 발행 오류와 AI 최종 실패를 구분한다.
        lastErrorCode = finalStatus == ProfilingResultStatus.FAILED
                ? "AI_RESULT_FAILED"
                : null;

        lastErrorMessage = finalStatus == ProfilingResultStatus.FAILED
                ? "AI가 최종 실패 결과를 반환했습니다."
                : null;

        return publishedAtInferred;
    }
}
