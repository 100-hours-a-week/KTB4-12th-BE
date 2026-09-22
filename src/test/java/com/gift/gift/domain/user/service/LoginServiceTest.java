package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.auth.dto.request.LoginRequest;
import com.gift.gift.domain.auth.service.LoginRateLimiter;
import com.gift.gift.domain.auth.service.LoginService;
import com.gift.gift.domain.auth.service.LoginSessionService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.auth.support.LoginResult;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.IssuedAccessToken;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String EMAIL =
            "user@example.com";

    private static final String RAW_PASSWORD =
            "Password1!";

    private static final String PASSWORD_HASH =
            "$2a$12$Qx1lH30rT4HaY8abG8h9xO9d6gSQ1v8"
                    + "VQqvxMDKsQFIhhnCC6Vfn2";

    private static final String ACCESS_TOKEN =
            "access-token";

    private static final String REFRESH_TOKEN =
            "a".repeat(43);

    private static final String REFRESH_TOKEN_HASH =
            "b".repeat(64);

    private static final Instant NOW =
            Instant.parse("2026-09-20T03:00:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginRateLimiter loginRateLimiter;

    @Mock
    private AccessTokenProvider accessTokenProvider;

    @Mock
    private RefreshTokenProvider refreshTokenProvider;

    @Mock
    private LoginSessionService loginSessionService;

    private LoginService loginService;
    private User user;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                NOW,
                ZoneId.of("Asia/Seoul")
        );

        loginService = new LoginService(
                userRepository,
                passwordEncoder,
                loginRateLimiter,
                accessTokenProvider,
                refreshTokenProvider,
                loginSessionService,
                clock
        );

        user = new User(
                EMAIL,
                PASSWORD_HASH,
                "김선물",
                LocalDate.of(2000, 1, 1)
        );

        ReflectionTestUtils.setField(
                user,
                "id",
                1L
        );
    }

    @Test
    @DisplayName("올바른 자격 증명은 제한 상태를 초기화하고 토큰과 세션을 생성한다")
    void login_returnsTokensAndUserWhenCredentialsMatch() {
        stubSuccessfulLogin();

        LoginResult result = loginService.login(
                request(),
                null
        );

        assertEquals(ACCESS_TOKEN, result.accessToken());
        assertEquals(3600, result.expiresIn());
        assertEquals(REFRESH_TOKEN, result.refreshToken());
        assertEquals(1L, result.userId());
        assertEquals(EMAIL, result.email());

        verify(loginRateLimiter)
                .checkEmailAttempt(EMAIL);

        verify(loginRateLimiter)
                .resetAfterSuccess(EMAIL);

        verify(loginSessionService)
                .saveLoginSession(
                        user,
                        null,
                        REFRESH_TOKEN_HASH,
                        java.time.LocalDateTime.ofInstant(
                                NOW,
                                ZoneId.of("Asia/Seoul")
                        ),
                        java.time.LocalDateTime.ofInstant(
                                NOW,
                                ZoneId.of("Asia/Seoul")
                        ).plusDays(14)
                );
    }

    @Test
    @DisplayName("가입되지 않은 이메일은 실패 횟수를 기록하고 동일한 401 오류를 반환한다")
    void login_rejectsUnknownEmail() {
        when(userRepository
                .findByEmailAndStatusAndDeletedAtIsNull(
                        EMAIL,
                        UserStatus.ACTIVE
                ))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginService.login(
                        request(),
                        null
                )
        );

        assertEquals(
                ErrorCode.INVALID_CREDENTIALS,
                exception.getErrorCode()
        );

        verify(loginRateLimiter)
                .recordFailure(EMAIL);
    }

    @Test
    @DisplayName("비밀번호 불일치는 실패 횟수를 기록하고 동일한 401 오류를 반환한다")
    void login_rejectsPasswordMismatch() {
        when(userRepository
                .findByEmailAndStatusAndDeletedAtIsNull(
                        EMAIL,
                        UserStatus.ACTIVE
                ))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                RAW_PASSWORD,
                PASSWORD_HASH
        )).thenReturn(false);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> loginService.login(
                        request(),
                        null
                )
        );

        assertEquals(
                ErrorCode.INVALID_CREDENTIALS,
                exception.getErrorCode()
        );

        verify(loginRateLimiter)
                .recordFailure(EMAIL);
    }

    private void stubSuccessfulLogin() {
        when(userRepository
                .findByEmailAndStatusAndDeletedAtIsNull(
                        EMAIL,
                        UserStatus.ACTIVE
                ))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                RAW_PASSWORD,
                PASSWORD_HASH
        )).thenReturn(true);

        when(accessTokenProvider.issue(1L))
                .thenReturn(
                        new IssuedAccessToken(
                                ACCESS_TOKEN,
                                NOW,
                                NOW.plus(
                                        1,
                                        ChronoUnit.HOURS
                                )
                        )
                );

        when(refreshTokenProvider.generate())
                .thenReturn(REFRESH_TOKEN);

        when(refreshTokenProvider.hash(REFRESH_TOKEN))
                .thenReturn(REFRESH_TOKEN_HASH);
    }

    private LoginRequest request() {
        return new LoginRequest(
                EMAIL,
                RAW_PASSWORD
        );
    }
}
