package com.gift.gift.domain.friend.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.friend.entity.Friend;

public interface FriendRepository extends JpaRepository<Friend, Long> {

    boolean existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
            Long userId,
            Long friendUserId
    );

    Optional<Friend> findByUser_IdAndFriendUser_Id(
            Long userId,
            Long friendUserId
    );
}
