package com.gift.gift.domain.recommendation.profiling.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.recommendation.profiling.exception.RecommendationErrorCode;
import com.gift.gift.domain.recommendation.profiling.exception.RecommendationException;
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
                        constraint = "analyzed_source_version <= source_version"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipientProfile extends BaseTimeEntity {

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
    @Column(name = "analyzed_source_version", nullable = false)
    @ColumnDefault("0")
    private long analyzedSourceVersion;

    @Column(name = "last_changed_at")
    private LocalDateTime lastChangedAt;

    @Column(name = "window_started_at")
    private LocalDateTime windowStartedAt;

    @Column(name = "pending_since")
    private LocalDateTime pendingSince;

    public RecipientProfile(User recipient) {
        this.recipient = Objects.requireNonNull(
                recipient,
                "수신자는 null일 수 없습니다."
        );
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
        sourceVersion = Math.addExact(sourceVersion, 1);
        return sourceVersion;
    }

    public void clearPendingChange() {
        lastChangedAt = null;
        windowStartedAt = null;
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
        if (!shouldApplyCallback(completedSourceVersion)) {
            return;
        }

        analyzedSourceVersion = completedSourceVersion;

        // 중간 버전의 결과는 최신 요청의 대기 상태를 바꾸지 않는다.
        if (completedSourceVersion < sourceVersion) {
            return;
        }

        profileStatus = RecipientProfileStatus.COMPLETED;
        pendingSince = null;
    }

    public void markFailed() {
        if (profileStatus != RecipientProfileStatus.PENDING) {
            throw new IllegalStateException(
                    "PENDING 상태의 프로파일만 실패 처리할 수 있습니다."
            );
        }

        profileStatus = RecipientProfileStatus.FAILED;
        pendingSince = null;
    }

    public boolean isDispatchDue(
            LocalDateTime now,
            Duration quietPeriod,
            Duration maximumWindow
    ) {
        Objects.requireNonNull(now, "현재 시각은 null일 수 없습니다.");
        Objects.requireNonNull(
                quietPeriod,
                "디바운스 시간은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                maximumWindow,
                "최대 대기 시간은 null일 수 없습니다."
        );

        if (lastChangedAt == null || windowStartedAt == null) {
            return false;
        }

        return !lastChangedAt.isAfter(now.minus(quietPeriod))
                || !windowStartedAt.isAfter(now.minus(maximumWindow));
    }

    public boolean shouldApplyCallback(long callbackSourceVersion) {
        if (callbackSourceVersion < 0
                || callbackSourceVersion > sourceVersion) {
            throw new RecommendationException(
                    RecommendationErrorCode.INVALID_CALLBACK_VERSION
            );
        }

        if (callbackSourceVersion < analyzedSourceVersion) {
            throw new RecommendationException(
                    RecommendationErrorCode.STALE_SOURCE_VERSION
            );
        }

        if (callbackSourceVersion == 0) {
            throw new RecommendationException(
                    RecommendationErrorCode.SOURCE_VERSION_NOT_DISPATCHED
            );
        }

        return callbackSourceVersion != analyzedSourceVersion;
    }
}
