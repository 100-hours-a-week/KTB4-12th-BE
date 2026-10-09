package com.gift.gift.domain.recommendation.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.recommendation.exception.RecommendationErrorCode;
import com.gift.gift.domain.recommendation.exception.RecommendationException;
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
                        constraint = "analyzed_source_version <= source_version"
                ),
                @CheckConstraint(
                        name = "chk_recipient_profiles_retry_count",
                        constraint = "retry_count BETWEEN 0 AND 2"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipientProfile extends BaseTimeEntity {

    // DB 저장 상한. 일반 요청의 디바운스 재시작은 1회만 허용한다.
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
    @Column(name = "analyzed_source_version", nullable = false)
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

        // 사용자 신규 변경에는 새로운 재시작 기회를 부여한다.
        resetRetryCount();
    }

    public long createNextSourceVersion() {
        // PENDING 중에도 새 변경의 번호를 생성할 수 있다.
        // 번호 생성 자체는 디바운스 재시작 횟수를 초기화하지 않는다.
        sourceVersion = Math.addExact(sourceVersion, 1);
        return sourceVersion;
    }

    public void restartDebounce(LocalDateTime now) {
        Objects.requireNonNull(
                now,
                "재시작 시각은 null일 수 없습니다."
        );

        // quiet-period와 maximum-window를 모두 다시 시작한다.
        lastChangedAt = now;
        windowStartedAt = now;
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

        // 중간 번호의 결과는 최신 요청의 대기 정보를 변경하지 않는다.
        if (completedSourceVersion < sourceVersion) {
            return;
        }

        profileStatus = RecipientProfileStatus.COMPLETED;
        pendingSince = null;

        // 늦은 콜백이 신규 변경이나 실패 재시작의 횟수를 지우지 않는다.
        if (lastChangedAt == null && windowStartedAt == null) {
            resetRetryCount();
        }
    }

    public void markFailed() {
        requirePendingStatus();
        profileStatus = RecipientProfileStatus.FAILED;
        pendingSince = null;
    }

    public int increaseRetryCount() {
        if (retryCount >= MAX_RETRY_COUNT) {
            throw new IllegalStateException(
                    "프로파일 재시도 횟수의 저장 상한을 초과했습니다."
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

        LocalDateTime quietPeriodCutoff = now.minus(quietPeriod);
        LocalDateTime maximumWindowCutoff = now.minus(maximumWindow);

        return !lastChangedAt.isAfter(quietPeriodCutoff)
                || !windowStartedAt.isAfter(maximumWindowCutoff);
    }

    public void applyAcceptedResponse(
            long acceptedSourceVersion,
            LocalDateTime snapshottedLastChangedAt,
            LocalDateTime acceptedAt
    ) {
        Objects.requireNonNull(
                snapshottedLastChangedAt,
                "요청 당시 변경 시각은 null일 수 없습니다."
        );
        Objects.requireNonNull(
                acceptedAt,
                "AI 요청 접수 시각은 null일 수 없습니다."
        );

        // 더 최신 번호가 이미 준비됐다면 옛 202를 반영하지 않는다.
        if (acceptedSourceVersion < sourceVersion) {
            return;
        }

        if (acceptedSourceVersion > sourceVersion) {
            throw new IllegalStateException(
                    "접수된 요청 버전이 현재 프로파일 버전과 일치하지 않습니다."
            );
        }

        // 빠른 콜백이 완료한 상태를 PENDING으로 되돌리지 않는다.
        if (analyzedSourceVersion < acceptedSourceVersion) {
            profileStatus = RecipientProfileStatus.PENDING;
            pendingSince = acceptedAt;
        }

        // 해당 변경의 접수 성공인 경우에만 대기와 횟수를 정리한다.
        if (Objects.equals(lastChangedAt, snapshottedLastChangedAt)) {
            clearPendingChange();
            resetRetryCount();
        }
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

        // 초기 번호 0은 실제 발송 또는 저장 완료를 의미하지 않는다.
        if (callbackSourceVersion == 0) {
            throw new RecommendationException(
                    RecommendationErrorCode.SOURCE_VERSION_NOT_DISPATCHED
            );
        }

        return callbackSourceVersion != analyzedSourceVersion;
    }
}
