package com.gift.gift.domain.friend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.repository.FriendRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendQueryServiceTest {

    private FriendRepository friendRepository;
    private FriendQueryService friendQueryService;

    @BeforeEach
    void setUp() {
        friendRepository = mock(FriendRepository.class);
        friendQueryService = new FriendQueryService(friendRepository);
    }

    @Test
    @DisplayName("삭제되지 않은 단방향 친구 관계가 존재하면 true를 반환한다")
    void areFriends_returnsTrue_whenActiveDirectionalRelationExists() {
        Long userId = 1L;
        Long friendUserId = 2L;
        when(friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(userId, friendUserId))
                .thenReturn(true);

        boolean result = friendQueryService.areFriends(userId, friendUserId);

        assertThat(result).isTrue();
        verify(friendRepository).existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(userId, friendUserId);
    }

    @Test
    @DisplayName("삭제되지 않은 단방향 친구 관계가 없으면 false를 반환한다")
    void areFriends_returnsFalse_whenActiveDirectionalRelationDoesNotExist() {
        Long userId = 1L;
        Long friendUserId = 2L;
        when(friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(userId, friendUserId))
                .thenReturn(false);

        boolean result = friendQueryService.areFriends(userId, friendUserId);

        assertThat(result).isFalse();
        verify(friendRepository).existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(userId, friendUserId);
    }
}
