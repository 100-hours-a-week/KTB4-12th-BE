package com.gift.gift.domain.notification.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(
        name = "notifications",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_notifications_deduplication_key",
                columnNames = "deduplication_key"
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "recipient_id", nullable = false)
    private Long recipientId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private NotificationType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", length = 50)
    private NotificationReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, length = 255)
    private String message;

    @Column(name = "deduplication_key", nullable = false, length = 120)
    private String deduplicationKey;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Notification(
            Long recipientId,
            NotificationType type,
            NotificationReferenceType referenceType,
            Long referenceId,
            String title,
            String message,
            String deduplicationKey
    ) {
        if ((referenceType == null) != (referenceId == null)) {
            throw new IllegalArgumentException("referenceType과 referenceId는 함께 존재하거나 함께 없어야 합니다.");
        }

        this.recipientId = Objects.requireNonNull(recipientId, "recipientId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.title = Objects.requireNonNull(title, "title must not be null");
        this.message = Objects.requireNonNull(message, "message must not be null");
        this.deduplicationKey = Objects.requireNonNull(deduplicationKey, "deduplicationKey must not be null");
    }

    public void markAsRead(LocalDateTime readAt) {
        Objects.requireNonNull(readAt, "readAt must not be null");

        if (this.readAt == null) {
            this.readAt = readAt;
        }
    }

    public boolean isRead() {
        return readAt != null;
    }
}
