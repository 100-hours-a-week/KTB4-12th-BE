package com.gift.gift.domain.friend.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(name = "friend_requests")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FriendRequest extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requester_id", nullable = false, updatable = false)
    private User requester;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receiver_id", nullable = false, updatable = false)
    private User receiver;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private FriendRequestStatus status;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public FriendRequest(User requester, User receiver) {
        this.requester = Objects.requireNonNull(requester, "requester must not be null");
        this.receiver = Objects.requireNonNull(receiver, "receiver must not be null");
        status = FriendRequestStatus.PENDING;
    }

    public void complete(FriendRequestStatus next) {
        Objects.requireNonNull(next, "next must not be null");
        if (status != FriendRequestStatus.PENDING || next == FriendRequestStatus.PENDING) {
            throw new IllegalStateException("종료된 요청은 다시 변경할 수 없습니다.");
        }
        status = next;
    }
}
