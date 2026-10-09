package com.gift.gift.domain.friend.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gift.gift.domain.friend.entity.Friendship;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Friendship f WHERE f.user1.id = :user1Id AND f.user2.id = :user2Id")
    Optional<Friendship> findPairForUpdate(Long user1Id, Long user2Id);

    boolean existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
            Long userId,
            Long friendUserId
    );

    Optional<Friendship> findByUser1_IdAndUser2_Id(
            Long userId,
            Long friendUserId
    );
}
