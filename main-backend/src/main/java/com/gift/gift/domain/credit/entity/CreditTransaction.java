package com.gift.gift.domain.credit.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Getter
@Entity
@Immutable
@Table(
        name = "credit_transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_credit_transactions_deduplication",
                columnNames = "deduplication_key"
        ),
        indexes = {
                @Index(
                        name = "idx_credit_transactions_user_created",
                        columnList = "user_id, created_at, id"
                ),
                @Index(
                        name = "idx_credit_transactions_order_record",
                        columnList = "order_record_id"
                ),
                @Index(
                        name = "idx_credit_transactions_gift_history",
                        columnList = "gift_history_id"
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_credit_transactions_type",
                        constraint = """
                                type IN (
                                    'SIGNUP_REWARD',
                                    'FRIENDSHIP_REWARD',
                                    'DAILY_ATTENDANCE_REWARD',
                                    'PAYMENT_USE',
                                    'PAYMENT_REFUND',
                                    'GIFT_COMPLETED_REWARD'
                                )
                                """
                ),
                @CheckConstraint(
                        name = "chk_credit_transactions_amount_positive",
                        constraint = "amount > 0"
                ),
                @CheckConstraint(
                        name = "chk_credit_transactions_balance_non_negative",
                        constraint = "balance_after >= 0"
                )
        }
)
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "user_id",
            nullable = false,
            updatable = false
    )
    private Long userId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(
            name = "type",
            nullable = false,
            updatable = false,
            length = 32
    )
    private CreditTransactionType type;

    @Column(
            name = "amount",
            nullable = false,
            updatable = false
    )
    private Long amount;

    @Column(
            name = "balance_after",
            nullable = false,
            updatable = false
    )
    private Long balanceAfter;

    @Column(
            name = "order_record_id",
            updatable = false
    )
    private Long orderRecordId;

    @Column(
            name = "gift_history_id",
            updatable = false
    )
    private Long giftHistoryId;

    @Column(
            name = "attendance_date",
            updatable = false
    )
    private LocalDate attendanceDate;

    @Column(
            name = "deduplication_key",
            nullable = false,
            updatable = false,
            length = 120
    )
    private String deduplicationKey;

    @CreatedDate
    @Column(
            name = "created_at",
            nullable = false,
            updatable = false
    )
    private LocalDateTime createdAt;

    public CreditTransaction(
            Long userId,
            CreditTransactionType type,
            long amount,
            long balanceAfter,
            Long orderRecordId,
            Long giftHistoryId,
            LocalDate attendanceDate,
            String deduplicationKey
    ) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "사용자 ID는 양수여야 합니다."
            );
        }

        if (amount <= 0) {
            throw new IllegalArgumentException(
                    "크레딧 변동 금액은 양수여야 합니다."
            );
        }

        if (balanceAfter < 0) {
            throw new IllegalArgumentException(
                    "거래 후 크레딧 잔액은 음수일 수 없습니다."
            );
        }

        if (orderRecordId != null && orderRecordId <= 0) {
            throw new IllegalArgumentException(
                    "주문 레코드 ID는 양수여야 합니다."
            );
        }

        if (giftHistoryId != null && giftHistoryId <= 0) {
            throw new IllegalArgumentException(
                    "선물 이력 ID는 양수여야 합니다."
            );
        }

        if (deduplicationKey == null
                || deduplicationKey.isBlank()
                || deduplicationKey.length() > 120) {
            throw new IllegalArgumentException(
                    "원장 중복 방지 키는 1자 이상 120자 이하여야 합니다."
            );
        }

        this.userId = userId;
        this.type = Objects.requireNonNull(
                type,
                "크레딧 거래 유형은 필수입니다."
        );
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.orderRecordId = orderRecordId;
        this.giftHistoryId = giftHistoryId;
        this.attendanceDate = attendanceDate;
        this.deduplicationKey = deduplicationKey;
    }
}
