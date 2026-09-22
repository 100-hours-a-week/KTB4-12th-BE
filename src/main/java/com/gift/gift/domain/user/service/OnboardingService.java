package com.gift.gift.domain.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.dto.response.CompleteOnboardingResponse;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final UserRepository userRepository;

    @Transactional
    public CompleteOnboardingResponse completeOnboarding(
            Long userId
    ) {
        User user = userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                )
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.USER_NOT_FOUND
                ));

        user.completeOnboarding();

        userRepository.flush();

        return CompleteOnboardingResponse.from(user);
    }
}
