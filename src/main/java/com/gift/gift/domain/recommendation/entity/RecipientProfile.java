package com.gift.gift.domain.recommendation.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "recipient_profiles",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_recipient_profiles_recipient",
                        columnNames = "recipient_id"
                )
        },
        indexes = {
                @Index(
                        name = "idx_recipient_profiles_last_changed",
                        columnList = "profile_status, last_changed_at, id"
                ),
                @Index(
                        name = "idx_recipient_profiles_window_started",
                        columnList = "profile_status, window_started_at, id"
                ),
                @Index(
                        name = "idx_recipient_profiles_pending_timeout",
                        columnList = "profile_status, pending_since, id"
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_recipient_profiles_status",
                        constraint = """
                                profile_status IN (
                                    'NONE',
                                    'PENDING',
                                    'COMPLETED',
                                    'FAILED'
                                )
                                """
                ),
                @CheckConstraint(
                        name = "chk_recipient_profiles_source_version",
                        constraint = "source_version >= 0"
                ),
                @CheckConstraint(
                        name = "chk_recipient_profiles_analyzed_version",
                        constraint = "analyzed_source_version >= 0"
                ),
                @CheckConstraint(
                        name = "chk_recipient_profiles_version_order",
                        constraint = """
                                analyzed_source_version <= source_version
                                """
                ),
                @CheckConstraint(
                        name = "chk_recipient_profiles_retry_count",
                        constraint = "retry_count BETWEEN 0 AND 2"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipientProfile extends BaseTimeEntity {

    public static final int MAX_RETRY_COUNT = 2;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "recipient_id",
            nullable = false,
            updatable = false,
            unique = true,
            foreignKey = @ForeignKey(
                    name = "fk_recipient_profiles_recipient"
            )
    )
    private User recipient;

    @NotNull
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
            name = "profile_status",
            nullable = false,
            length = 20
    )
    @ColumnDefault("'NONE'")
    private RecipientProfileStatus profileStatus =
            RecipientProfileStatus.NONE;

    @Min(0)
    @Column(name = "source_version", nullable = false)
    @ColumnDefault("0")
    private long sourceVersion;

    @Min(0)
    @Column(
            name = "analyzed_source_version",
            nullable = false
    )
    @ColumnDefault("0")
    private long analyzedSourceVersion;

    @Column(name = "last_changed_at")
    private LocalDateTime lastChangedAt;

    @Column(name = "window_started_at")
    private LocalDateTime windowStartedAt;

    @Column(name = "pending_since")
    private LocalDateTime pendingSince;

    @Min(0)
    @Max(MAX_RETRY_COUNT)
    @Column(
            name = "retry_count",
            nullable = false,
            columnDefinition = "TINYINT UNSIGNED"
    )
    @ColumnDefault("0")
    private int retryCount;

    public RecipientProfile(User recipient) {
        this.recipient = Objects.requireNonNull(
                recipient,
                "수신자는 null일 수 없습니다."
        );

        this.profileStatus = RecipientProfileStatus.NONE;
        this.sourceVersion = 0;
        this.analyzedSourceVersion = 0;
        this.retryCount = 0;
    }

    public void recordPreferenceChange(LocalDateTime changedAt) {
        Objects.requireNonNull(
                changedAt,
                "비선호 변경 시각은 null일 수 없습니다."
        );

        if (lastChangedAt != null
                && changedAt.isBefore(lastChangedAt)) {
            throw new IllegalArgumentException(
                    "변경 시각은 기존 마지막 변경 시각보다 이전일 수 없습니다."
            );
        }

        if (windowStartedAt == null) {
            windowStartedAt = changedAt;
        }

        lastChangedAt = changedAt;
    }

    public long createNextSourceVersion() {
        if (profileStatus == RecipientProfileStatus.PENDING) {
            throw new IllegalStateException(
                    "처리 중인 요청이 있는 동안 새 버전을 생성할 수 없습니다."
            );
        }

        sourceVersion = Math.addExact(sourceVersion, 1);
        resetRetryCount();

        return sourceVersion;
    }

    public void markPending(LocalDateTime startedAt) {
        Objects.requireNonNull(
                startedAt,
                "PENDING 시작 시각은 null일 수 없습니다."
        );

        if (sourceVersion <= analyzedSourceVersion) {
            throw new IllegalStateException(
                    "분석이 필요한 새 요청 버전이 없습니다."
            );
        }

        profileStatus = RecipientProfileStatus.PENDING;
        pendingSince = startedAt;
    }

    public void markCompleted(long completedSourceVersion) {
        requirePendingStatus();

        if (completedSourceVersion != sourceVersion) {
            throw new IllegalArgumentException(
                    "현재 요청 버전과 완료된 결과 버전이 일치하지 않습니다."
            );
        }

        analyzedSourceVersion = completedSourceVersion;
        profileStatus = RecipientProfileStatus.COMPLETED;
        pendingSince = null;
        resetRetryCount();
    }

    public void markFailed() {
        requirePendingStatus();

        profileStatus = RecipientProfileStatus.FAILED;
        pendingSince = null;
    }

    public int increaseRetryCount() {
        if (retryCount >= MAX_RETRY_COUNT) {
            throw new IllegalStateException(
                    "AI 추천 요청은 최대 2회까지 재시도할 수 있습니다."
            );
        }

        retryCount++;
        return retryCount;
    }

    public void resetRetryCount() {
        retryCount = 0;
    }

    private void requirePendingStatus() {
        if (profileStatus != RecipientProfileStatus.PENDING) {
            throw new IllegalStateException(
                    "PENDING 상태의 프로파일만 완료 또는 실패 처리할 수 있습니다."
            );
        }
    }
}
