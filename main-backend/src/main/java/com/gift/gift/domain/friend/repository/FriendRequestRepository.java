package com.gift.gift.domain.friend.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gift.gift.domain.friend.entity.FriendRequest;

public interface FriendRequestRepository extends JpaRepository<FriendRequest, Long> {

    @Query("""
            SELECT r FROM FriendRequest r
            WHERE r.status = com.gift.gift.domain.friend.entity.FriendRequestStatus.PENDING
              AND ((r.requester.id = :firstId AND r.receiver.id = :secondId)
                OR (r.requester.id = :secondId AND r.receiver.id = :firstId))
            """)
    Optional<FriendRequest> findPendingPair(Long firstId, Long secondId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM FriendRequest r WHERE r.id = :requestId")
    Optional<FriendRequest> findForUpdate(Long requestId);

    @Query("""
            SELECT r FROM FriendRequest r
            JOIN FETCH r.requester requester JOIN FETCH r.receiver receiver
            WHERE r.status = com.gift.gift.domain.friend.entity.FriendRequestStatus.PENDING
              AND ((:received = true AND receiver.id = :userId)
                OR (:received = false AND requester.id = :userId))
              AND requester.status = com.gift.gift.domain.user.entity.UserStatus.ACTIVE
              AND requester.deletedAt IS NULL
              AND receiver.status = com.gift.gift.domain.user.entity.UserStatus.ACTIVE
              AND receiver.deletedAt IS NULL
              AND r.deletedAt IS NULL
              AND (:beforeCreatedAt IS NULL OR r.createdAt < :beforeCreatedAt
                OR (r.createdAt = :beforeCreatedAt AND r.id < :beforeId))
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<FriendRequest> findPendingList(
            Long userId, boolean received, LocalDateTime beforeCreatedAt, Long beforeId, Pageable pageable
    );
}
