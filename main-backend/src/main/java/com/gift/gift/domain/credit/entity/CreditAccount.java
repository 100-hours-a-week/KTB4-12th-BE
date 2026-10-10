package com.gift.gift.domain.credit.entity;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "credit_accounts",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_credit_accounts_user",
                columnNames = "user_id"
        ),
        check = @CheckConstraint(
                name = "chk_credit_accounts_balance_non_negative",
                constraint = "balance >= 0"
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CreditAccount extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            name = "user_id",
            nullable = false,
            updatable = false
    )
    private Long userId;

    @Column(name = "balance", nullable = false)
    @ColumnDefault("0")
    private Long balance;

    public CreditAccount(Long userId, long balance) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException(
                    "사용자 ID는 양수여야 합니다."
            );
        }

        if (balance < 0) {
            throw new IllegalArgumentException(
                    "크레딧 잔액은 음수일 수 없습니다."
            );
        }

        this.userId = userId;
        this.balance = balance;
    }
}
