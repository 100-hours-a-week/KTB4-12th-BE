package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.LocalDate;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.gift.service.GiftQueryService;
import com.gift.gift.domain.user.dto.request.UpdateUserProfileRequest;
import com.gift.gift.domain.user.dto.response.UpdateUserProfileResponse;
import com.gift.gift.domain.user.dto.response.UserProfileResponse;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final GiftQueryService giftQueryService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public UserProfileResponse getMyProfile(Long userId) {
        User user = findActiveUser(userId);
        LocalDate today = LocalDate.now(clock);

        GiftCountRow giftCount =
                giftQueryService.getYearlyGiftCount(
                        userId,
                        today
                );

        return UserProfileResponse.from(
                user,
                giftCount,
                today,
                clock.getZone()
        );
    }

    @Transactional
    public UpdateUserProfileResponse updateMyProfile(
            Long userId,
            UpdateUserProfileRequest request
    ) {
        User user = findActiveUser(userId);

        if (request.hasBirth()) {
            user.updateBirth(request.birthValue());
        }

        if (request.hasBirthdayPublic()) {
            user.updateBirthdayPublic(
                    request.birthdayPublicValue()
            );
        }

        userRepository.flush();

        return UpdateUserProfileResponse.from(
                user,
                clock.getZone()
        );
    }

    private User findActiveUser(Long userId) {
        return userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                )
                .orElseThrow(() -> new UserException(
                        UserErrorCode.USER_NOT_FOUND
                ));
    }
}
