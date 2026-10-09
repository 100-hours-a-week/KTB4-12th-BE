package com.gift.gift.domain.preference.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(name = "user_gift_preferences", uniqueConstraints = @UniqueConstraint(
        name = "uk_user_gift_preferences_user", columnNames = "user_id"
))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserGiftPreference extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_user_gift_preferences_user"))
    private User user;

    @Column(name = "preference", columnDefinition = "TEXT")
    private String preference;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public UserGiftPreference(User user, String preference) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.preference = preference;
    }

    public void changePreference(String preference) {
        this.preference = preference;
    }
}
