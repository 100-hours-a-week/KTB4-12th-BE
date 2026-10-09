package com.gift.gift.domain.user.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.support.ActiveUserSummary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class UserQueryServiceTest {

    private UserRepository userRepository;
    private UserQueryService userQueryService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        userQueryService = new UserQueryService(
                userRepository
        );
    }

    @Test
    @DisplayName("활성 사용자가 존재하면 ID와 이름을 반환한다")
    void findActiveUser_returnsSummary_whenActiveUserExists() {
        Long userId = 2L;
        User user = mock(User.class);

        when(user.getId()).thenReturn(userId);
        when(user.getName()).thenReturn("수신자");
        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                userId,
                UserStatus.ACTIVE
        )).thenReturn(Optional.of(user));

        Optional<ActiveUserSummary> result =
                userQueryService.findActiveUser(userId);

        assertThat(result).contains(
                new ActiveUserSummary(
                        userId,
                        "수신자"
                )
        );

        verify(userRepository)
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                );
    }

    @Test
    @DisplayName("활성 사용자가 없으면 빈 결과를 반환한다")
    void findActiveUser_returnsEmpty_whenActiveUserDoesNotExist() {
        Long userId = 2L;

        when(userRepository.findByIdAndStatusAndDeletedAtIsNull(
                userId,
                UserStatus.ACTIVE
        )).thenReturn(Optional.empty());

        Optional<ActiveUserSummary> result =
                userQueryService.findActiveUser(userId);

        assertThat(result).isEmpty();

        verify(userRepository)
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                );
    }

    @Test
    @DisplayName("참조를 요청하면 조회 없이 Repository의 프록시 참조를 그대로 반환한다")
    void getReference_returnsRepositoryProxy_withoutQuery() {
        Long userId = 5L;
        User reference = mock(User.class);

        when(userRepository.getReferenceById(userId)).thenReturn(reference);

        User result = userQueryService.getReference(userId);

        assertThat(result).isSameAs(reference);

        verify(userRepository).getReferenceById(userId);
        verifyNoMoreInteractions(userRepository);
    }
}
