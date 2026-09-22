package com.gift.gift.domain.user.service;

import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.support.ActiveUserSummary;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserQueryService {

    private final UserRepository userRepository;

    public Optional<ActiveUserSummary> findActiveUser(
            Long userId
    ) {
        return userRepository
                .findByIdAndStatusAndDeletedAtIsNull(
                        userId,
                        UserStatus.ACTIVE
                )
                .map(ActiveUserSummary::from);
    }

    @Transactional
    public Optional<User> findActiveUserForUpdate(
            Long userId
    ) {
        return userRepository.findActiveByIdForUpdate(
                userId,
                UserStatus.ACTIVE
        );
    }
}
