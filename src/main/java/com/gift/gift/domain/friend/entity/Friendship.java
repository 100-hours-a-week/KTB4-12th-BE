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
        name = "friendships",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_friendships_pair",
                        columnNames = {"user_id_1", "user_id_2"}
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_friendships_distinct_users",
                        constraint = "user_id_1 < user_id_2"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Friendship extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id_1",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_friendships_user1")
    )
    private User user1;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id_2",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_friendships_user2")
    )
    private User user2;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Friendship(User user, User friendUser) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(friendUser, "friendUser must not be null");
        this.user1 = user.getId() <= friendUser.getId() ? user : friendUser;
        this.user2 = user.getId() <= friendUser.getId() ? friendUser : user;
    }

    public void remove(LocalDateTime now) {
        if (deletedAt == null) {
            deletedAt = Objects.requireNonNull(now, "now must not be null");
        }
    }

    public void restore() {
        deletedAt = null;
    }
}
