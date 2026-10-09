package com.gift.gift.domain.friend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.repository.FriendshipRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FriendQueryServiceTest {

    private FriendshipRepository friendRepository;
    private FriendQueryService friendQueryService;

    @BeforeEach
    void setUp() {
        friendRepository = mock(FriendshipRepository.class);
        friendQueryService = new FriendQueryService(friendRepository);
    }

    @Test
    @DisplayName("삭제되지 않은 양방향 친구 관계가 존재하면 true를 반환한다")
    void areFriends_returnsTrue_whenActiveDirectionalRelationExists() {
        Long userId = 1L;
        Long friendUserId = 2L;
        when(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(userId, friendUserId))
                .thenReturn(true);

        boolean result = friendQueryService.areFriends(userId, friendUserId);

        assertThat(result).isTrue();
        verify(friendRepository).existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(userId, friendUserId);
    }

    @Test
    @DisplayName("삭제되지 않은 양방향 친구 관계가 없으면 false를 반환한다")
    void areFriends_returnsFalse_whenActiveDirectionalRelationDoesNotExist() {
        Long userId = 1L;
        Long friendUserId = 2L;
        when(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(userId, friendUserId))
                .thenReturn(false);

        boolean result = friendQueryService.areFriends(userId, friendUserId);

        assertThat(result).isFalse();
        verify(friendRepository).existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(userId, friendUserId);
    }

    @Test
    @DisplayName("조회 방향과 무관하게 같은 정규화 사용자 쌍으로 친구 여부를 확인한다")
    void areFriends_normalizesBothDirections() {
        when(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(1L, 2L)).thenReturn(true);

        assertThat(friendQueryService.areFriends(1L, 2L)).isTrue();
        assertThat(friendQueryService.areFriends(2L, 1L)).isTrue();
        verify(friendRepository, org.mockito.Mockito.times(2))
                .existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(1L, 2L);
    }
}
