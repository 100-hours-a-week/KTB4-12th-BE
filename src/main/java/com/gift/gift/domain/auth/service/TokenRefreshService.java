package com.gift.gift.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.auth.entity.UserSession;
import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.repository.UserSessionRepository;
import com.gift.gift.domain.auth.support.TokenRefreshResult;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.IssuedAccessToken;
import com.gift.gift.global.security.RefreshTokenProvider;

@Service
@RequiredArgsConstructor
public class TokenRefreshService {

    private final UserSessionRepository sessionRepository;
    private final AccessTokenProvider accessTokenProvider;
    private final RefreshTokenProvider refreshTokenProvider;
    private final Clock clock;

    @Transactional
    public TokenRefreshResult refresh(
            String refreshToken
    ) {
        validateRefreshTokenFormat(refreshToken);

        String currentRefreshTokenHash =
                refreshTokenProvider.hash(
                        refreshToken
                );

        UserSession session = findSessionForUpdate(
                currentRefreshTokenHash
        );

        LocalDateTime now = LocalDateTime.now(clock);

        validateSession(session, now);

        IssuedAccessToken issuedAccessToken =
                accessTokenProvider.issue(
                        session.getUser().getId()
                );

        String newRefreshToken =
                refreshTokenProvider.generate();

        String newRefreshTokenHash =
                refreshTokenProvider.hash(
                        newRefreshToken
                );

        session.rotateRefreshToken(
                newRefreshTokenHash,
                now
        );

        sessionRepository.flush();

        long expiresIn = Duration.between(
                issuedAccessToken.issuedAt(),
                issuedAccessToken.expiresAt()
        ).toSeconds();

        return new TokenRefreshResult(
                issuedAccessToken.value(),
                expiresIn,
                newRefreshToken
        );
    }

    private void validateRefreshTokenFormat(
            String refreshToken
    ) {
        if (!refreshTokenProvider.isValidFormat(
                refreshToken
        )) {
            throw invalidRefreshToken();
        }
    }

    private UserSession findSessionForUpdate(
            String refreshTokenHash
    ) {
        try {
            return sessionRepository
                    .findByRefreshTokenHashForUpdate(
                            refreshTokenHash
                    )
                    .orElseThrow(
                            this::invalidRefreshToken
                    );
        } catch (
                PessimisticLockingFailureException
                | QueryTimeoutException exception
        ) {
            throw new AuthException(
                    AuthErrorCode.TOKEN_REFRESH_CONFLICT
            );
        }
    }

    private void validateSession(
            UserSession session,
            LocalDateTime now
    ) {
        if (!session.isActive(now)
                || !session.getUser().isActive()) {
            throw invalidRefreshToken();
        }
    }

    private AuthException invalidRefreshToken() {
        return new AuthException(
                AuthErrorCode.INVALID_REFRESH_TOKEN
        );
    }
}
