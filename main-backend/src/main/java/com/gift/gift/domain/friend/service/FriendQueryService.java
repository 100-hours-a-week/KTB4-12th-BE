package com.gift.gift.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.repository.FriendshipRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendQueryService {

    private final FriendshipRepository friendRepository;

    public boolean areFriends(Long userId, Long friendUserId) {
        return friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
                Math.min(userId, friendUserId),
                Math.max(userId, friendUserId)
        );
    }
}
