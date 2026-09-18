package com.gift.gift.domain.friend.entity;

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
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "friends",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_friends_user_friend_user",
                        columnNames = {"user_id", "friend_user_id"}
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_friends_distinct_users",
                        constraint = "user_id <> friend_user_id"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Friend extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_friends_user")
    )
    private User user;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "friend_user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_friends_friend_user")
    )
    private User friendUser;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Friend(User user, User friendUser) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.friendUser = Objects.requireNonNull(friendUser, "friendUser must not be null");
    }
}
