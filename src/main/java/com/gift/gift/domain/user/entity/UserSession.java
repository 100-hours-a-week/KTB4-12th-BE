package com.gift.gift.domain.user.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "user_sessions",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_user_sessions_refresh_token",
                        columnNames = "refresh_token"
                )
        },
        indexes = {
                @Index(
                        name = "idx_user_sessions_user",
                        columnList = "user_id"
                ),
                @Index(
                        name = "idx_user_sessions_expires",
                        columnList = "expires_at"
                ),
                @Index(
                        name = "idx_user_sessions_revoked",
                        columnList = "revoked_at"
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_user_sessions_expires_after_auth",
                        constraint = "expires_at > authenticated_at"
                ),
                @CheckConstraint(
                        name = "chk_user_sessions_revoked_after_created",
                        constraint =
                                "revoked_at IS NULL OR revoked_at >= created_at"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserSession extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_user_sessions_user"
            )
    )
    private User user;

    @NotBlank
    @Pattern(regexp = "^[0-9a-f]{64}$")
    @Column(
            name = "refresh_token",
            nullable = false,
            length = 64
    )
    private String refreshTokenHash;

    @NotNull
    @Column(name = "authenticated_at", nullable = false)
    private LocalDateTime authenticatedAt;

    @NotNull
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public UserSession(
            User user,
            String refreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        this.user = Objects.requireNonNull(
                user,
                "user must not be null"
        );
        this.refreshTokenHash = requireRefreshTokenHash(
                refreshTokenHash
        );
        this.authenticatedAt = Objects.requireNonNull(
                authenticatedAt,
                "authenticatedAt must not be null"
        );
        this.expiresAt = requireExpiresAfterAuthentication(
                authenticatedAt,
                expiresAt
        );
    }

    public boolean isActive(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");

        return revokedAt == null
                && deletedAt == null
                && expiresAt.isAfter(now);
    }

    public boolean belongsTo(Long userId) {
        return userId != null
                && user.getId().equals(userId);
    }

    public void reauthenticate(
            String newRefreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt,
            LocalDateTime now
    ) {
        if (!isActive(now)) {
            throw new IllegalStateException(
                    "Inactive session cannot be reauthenticated"
            );
        }

        this.refreshTokenHash = requireRefreshTokenHash(
                newRefreshTokenHash
        );
        this.authenticatedAt = Objects.requireNonNull(
                authenticatedAt,
                "authenticatedAt must not be null"
        );
        this.expiresAt = requireExpiresAfterAuthentication(
                authenticatedAt,
                expiresAt
        );
    }

    public void rotateRefreshToken(
            String newRefreshTokenHash,
            LocalDateTime now
    ) {
        if (!isActive(now)) {
            throw new IllegalStateException(
                    "Inactive session cannot rotate refresh token"
            );
        }

        this.refreshTokenHash = requireRefreshTokenHash(
                newRefreshTokenHash
        );
    }

    public boolean revoke(LocalDateTime now) {
        Objects.requireNonNull(now, "now must not be null");

        if (revokedAt != null) {
            return false;
        }

        revokedAt = now;
        return true;
    }

    private String requireRefreshTokenHash(
            String refreshTokenHash
    ) {
        Objects.requireNonNull(
                refreshTokenHash,
                "refreshTokenHash must not be null"
        );

        if (!refreshTokenHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException(
                    "refreshTokenHash must be lowercase SHA-256 hex"
            );
        }

        return refreshTokenHash;
    }

    private LocalDateTime requireExpiresAfterAuthentication(
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        Objects.requireNonNull(
                expiresAt,
                "expiresAt must not be null"
        );

        if (!expiresAt.isAfter(authenticatedAt)) {
            throw new IllegalArgumentException(
                    "expiresAt must be after authenticatedAt"
            );
        }

        return expiresAt;
    }
}
