package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.entity.UserSession;
import com.gift.gift.domain.user.repository.UserSessionRepository;
import com.gift.gift.global.security.RefreshTokenProvider;

@Service
@RequiredArgsConstructor
public class LogoutService {

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenProvider refreshTokenProvider;
    private final Clock clock;

    @Transactional
    public void logout(String refreshToken) {
        if (!refreshTokenProvider.isValidFormat(
                refreshToken
        )) {
            return;
        }

        String refreshTokenHash =
                refreshTokenProvider.hash(
                        refreshToken
                );

        sessionRepository
                .findByRefreshTokenHashForUpdate(
                        refreshTokenHash
                )
                .ifPresent(this::revokeIfNecessary);
    }

    private void revokeIfNecessary(
            UserSession session
    ) {
        if (session.getDeletedAt() != null
                || session.getRevokedAt() != null) {
            return;
        }

        if (session.revoke(
                LocalDateTime.now(clock)
        )) {
            sessionRepository.flush();
        }
    }
}
