package com.gift.gift.domain.user.service;

import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.user.dto.response.CompleteOnboardingResponse;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OnboardingServiceTest {

    private static final Long USER_ID = 1L;

    private UserRepository userRepository;
    private OnboardingService onboardingService;
    private User user;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        onboardingService =
                new OnboardingService(userRepository);

        user = new User(
                "user@example.com",
                "$2a$12$" + "a".repeat(53),
                "홍길동",
                LocalDate.of(1998, 3, 15)
        );
    }

    @Test
    @DisplayName("최초 온보딩 완료 요청은 isFirstLogin을 false로 변경한다")
    void completeOnboarding_changesFirstLoginState() {
        whenActiveUserExists();

        CompleteOnboardingResponse response =
                onboardingService.completeOnboarding(USER_ID);

        assertThat(user.isFirstLogin()).isFalse();
        assertThat(response.isFirstLogin()).isFalse();

        verify(userRepository).flush();
    }

    @Test
    @DisplayName("이미 완료된 회원의 반복 요청도 false를 반환한다")
    void completeOnboarding_isIdempotent() {
        user.completeOnboarding();
        whenActiveUserExists();

        CompleteOnboardingResponse response =
                onboardingService.completeOnboarding(USER_ID);

        assertThat(user.isFirstLogin()).isFalse();
        assertThat(response.isFirstLogin()).isFalse();

        verify(userRepository).flush();
    }

    @Test
    @DisplayName("활성 회원이 없으면 USER_NOT_FOUND를 발생시킨다")
    void completeOnboarding_throwsUserNotFound() {
        when(userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        USER_ID,
                        UserStatus.ACTIVE
                ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> onboardingService.completeOnboarding(USER_ID)
        ).isInstanceOfSatisfying(
                BusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ErrorCode.USER_NOT_FOUND)
        );
    }

    @Test
    @DisplayName("저장소 flush 실패는 호출자에게 전파한다")
    void completeOnboarding_propagatesPersistenceFailure() {
        whenActiveUserExists();

        doThrow(new DataAccessResourceFailureException(
                "user storage unavailable"
        )).when(userRepository).flush();

        assertThatThrownBy(
                () -> onboardingService.completeOnboarding(USER_ID)
        ).isInstanceOf(
                DataAccessResourceFailureException.class
        );
    }

    private void whenActiveUserExists() {
        when(userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        USER_ID,
                        UserStatus.ACTIVE
                ))
                .thenReturn(Optional.of(user));
    }
}
