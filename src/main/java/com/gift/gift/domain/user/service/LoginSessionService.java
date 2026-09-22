package com.gift.gift.domain.user.service;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserSession;
import com.gift.gift.domain.user.repository.UserSessionRepository;
import com.gift.gift.global.security.RefreshTokenProvider;

@Service
@RequiredArgsConstructor
public class LoginSessionService {

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenProvider refreshTokenProvider;

    @Transactional
    public void saveLoginSession(
            User user,
            String existingRefreshToken,
            String newRefreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        if (!refreshTokenProvider.isValidFormat(
                existingRefreshToken
        )) {
            createSession(
                    user,
                    newRefreshTokenHash,
                    authenticatedAt,
                    expiresAt
            );
            return;
        }

        String existingTokenHash =
                refreshTokenProvider.hash(
                        existingRefreshToken
                );

        UserSession existingSession =
                sessionRepository
                        .findByRefreshTokenHashForUpdate(
                                existingTokenHash
                        )
                        .orElse(null);

        if (existingSession == null
                || !existingSession.isActive(
                authenticatedAt
        )) {
            createSession(
                    user,
                    newRefreshTokenHash,
                    authenticatedAt,
                    expiresAt
            );
            return;
        }

        if (existingSession.belongsTo(user.getId())) {
            existingSession.reauthenticate(
                    newRefreshTokenHash,
                    authenticatedAt,
                    expiresAt,
                    authenticatedAt
            );
            sessionRepository.flush();
            return;
        }

        existingSession.revoke(authenticatedAt);

        createSession(
                user,
                newRefreshTokenHash,
                authenticatedAt,
                expiresAt
        );
    }

    private void createSession(
            User user,
            String refreshTokenHash,
            LocalDateTime authenticatedAt,
            LocalDateTime expiresAt
    ) {
        sessionRepository.saveAndFlush(
                new UserSession(
                        user,
                        refreshTokenHash,
                        authenticatedAt,
                        expiresAt
                )
        );
    }
}
