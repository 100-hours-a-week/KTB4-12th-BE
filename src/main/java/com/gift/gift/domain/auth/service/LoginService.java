package com.gift.gift.domain.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.auth.dto.request.LoginRequest;
import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.auth.support.LoginResult;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.IssuedAccessToken;
import com.gift.gift.global.security.RefreshTokenProvider;

@Service
@RequiredArgsConstructor
public class LoginService {

    private static final Duration REFRESH_TOKEN_TTL =
            Duration.ofDays(14);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;
    private final AccessTokenProvider accessTokenProvider;
    private final RefreshTokenProvider refreshTokenProvider;
    private final LoginSessionService loginSessionService;
    private final Clock clock;

    public LoginResult login(
            LoginRequest request,
            String existingRefreshToken
    ) {
        String email = request.email();

        loginRateLimiter.checkEmailAttempt(email);

        User user = userRepository
                .findByEmailAndStatusAndDeletedAtIsNull(
                        email,
                        UserStatus.ACTIVE
                )
                .orElse(null);

        if (!matchesCredentials(
                user,
                request.password()
        )) {
            loginRateLimiter.recordFailure(email);

            throw new AuthException(
                    AuthErrorCode.INVALID_CREDENTIALS
            );
        }

        loginRateLimiter.resetAfterSuccess(email);

        IssuedAccessToken accessToken =
                accessTokenProvider.issue(
                        user.getId()
                );

        String refreshToken =
                refreshTokenProvider.generate();

        String refreshTokenHash =
                refreshTokenProvider.hash(
                        refreshToken
                );

        LocalDateTime authenticatedAt =
                LocalDateTime.now(clock);

        LocalDateTime expiresAt =
                authenticatedAt.plus(
                        REFRESH_TOKEN_TTL
                );

        loginSessionService.saveLoginSession(
                user,
                existingRefreshToken,
                refreshTokenHash,
                authenticatedAt,
                expiresAt
        );

        long expiresIn = Duration.between(
                accessToken.issuedAt(),
                accessToken.expiresAt()
        ).toSeconds();

        return new LoginResult(
                accessToken.value(),
                expiresIn,
                refreshToken,
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.isFirstLogin()
        );
    }

    private boolean matchesCredentials(
            User user,
            String rawPassword
    ) {
        return user != null
                && passwordEncoder.matches(
                rawPassword,
                user.getPasswordHash()
        );
    }
}
