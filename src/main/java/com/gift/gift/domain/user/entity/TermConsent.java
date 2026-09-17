package com.gift.gift.domain.user.entity;

import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "term_consents",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_term_consents_user_term",
                        columnNames = {"user_id", "term_id"}
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TermConsent extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_term_consents_user")
    )
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "term_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_term_consents_term")
    )
    private Term term;

    @Column(name = "is_agreed", nullable = false)
    private boolean isAgreed;

    public TermConsent(User user, Term term, boolean isAgreed) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.term = Objects.requireNonNull(term, "term must not be null");
        this.isAgreed = isAgreed;
    }
}
