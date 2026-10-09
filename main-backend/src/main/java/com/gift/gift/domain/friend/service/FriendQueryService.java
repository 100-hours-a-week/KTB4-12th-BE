package com.gift.gift.domain.friend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.repository.FriendRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FriendQueryService {

    private final FriendRepository friendRepository;

    public boolean areFriends(Long userId, Long friendUserId) {
        return friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                userId,
                friendUserId
        );
    }
}
