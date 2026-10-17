package com.gift.gift.domain.recommendation.chat.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.gift.gift.domain.user.entity.User;

@Getter
@Entity
@Table(
        name = "ai_chat_turn_usages",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ai_chat_turn_usages_user_conversation_turn",
                columnNames = {"user_id", "conversation_id", "turn_id"}
        ),
        indexes = @Index(
                name = "idx_ai_chat_turn_usages_user_date",
                columnList = "user_id, usage_date"
        )
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiChatTurnUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_ai_chat_turn_usages_user")
    )
    private User user;

    @Column(
            name = "conversation_id",
            nullable = false,
            updatable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID conversationId;

    @Column(
            name = "turn_id",
            nullable = false,
            updatable = false,
            columnDefinition = "BINARY(16)"
    )
    private UUID turnId;

    @Column(name = "usage_date", nullable = false, updatable = false)
    private LocalDate usageDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public AiChatTurnUsage(
            User user,
            UUID conversationId,
            UUID turnId,
            LocalDate usageDate
    ) {
        this.user = Objects.requireNonNull(user, "사용자는 필수입니다.");
        this.conversationId = Objects.requireNonNull(
                conversationId,
                "대화 ID는 필수입니다."
        );
        this.turnId = Objects.requireNonNull(turnId, "질문 ID는 필수입니다.");
        this.usageDate = Objects.requireNonNull(
                usageDate,
                "이용 날짜는 필수입니다."
        );
    }
}
