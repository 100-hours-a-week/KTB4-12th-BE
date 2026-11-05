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
}
