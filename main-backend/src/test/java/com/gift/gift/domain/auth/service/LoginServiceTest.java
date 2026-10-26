package com.gift.gift.domain.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.auth.dto.request.LoginRequest;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.support.LoginResult;
import com.gift.gift.domain.credit.service.DailyAttendanceCreditService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.security.AccessTokenProvider;
import com.gift.gift.global.security.IssuedAccessToken;
import com.gift.gift.global.security.RefreshTokenProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginServiceTest {

    private static final String EMAIL = "user@example.com";

    private static final String RAW_PASSWORD = "Password1!";

    private static final String PASSWORD_HASH =
            "$2a$12$Qx1lH30rT4HaY8abG8h9xO9d6gSQ1v8" + "VQqvxMDKsQFIhhnCC6Vfn2";

    private static final String ACCESS_TOKEN = "access-token";

    private static final String REFRESH_TOKEN = "a".repeat(43);

    private static final String REFRESH_TOKEN_HASH = "b".repeat(64);

    private static final Instant NOW = Instant.parse("2026-09-20T03:00:00Z");

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

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

    @Mock
    private DailyAttendanceCreditService dailyAttendanceCreditService;

    private LoginService loginService;
    private User user;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, SEOUL);

        loginService = new LoginService(
                userRepository,
                passwordEncoder,
                loginRateLimiter,
                accessTokenProvider,
                refreshTokenProvider,
                loginSessionService,
                dailyAttendanceCreditService,
                clock
        );

        user = new User(EMAIL, PASSWORD_HASH, "김선물", LocalDate.of(2000, 1, 1));

        ReflectionTestUtils.setField(user, "id", 1L);
    }

    @Test
    @DisplayName("올바른 자격 증명은 세션 저장 후 일별 접속 크레딧을 지급한다")
    void login_returnsTokensAndGrantsDailyCredit() {

        stubSuccessfulLogin();

        LoginResult result = loginService.login(request(), null);

        assertEquals(ACCESS_TOKEN, result.accessToken());
        assertEquals(3600, result.expiresIn());
        assertEquals(REFRESH_TOKEN, result.refreshToken());
        assertEquals(1L, result.userId());
        assertEquals(EMAIL, result.email());

        verify(loginRateLimiter).checkEmailAttempt(EMAIL);

        verify(loginRateLimiter).resetAfterSuccess(EMAIL);

        LocalDateTime authenticatedAt = LocalDateTime.ofInstant(NOW, SEOUL);

        InOrder completionOrder = inOrder(loginSessionService, dailyAttendanceCreditService);

        completionOrder.verify(
                loginSessionService
        ).saveLoginSession(
                user,
                null,
                REFRESH_TOKEN_HASH,
                authenticatedAt,
                authenticatedAt.plusDays(14)
        );

        completionOrder.verify(dailyAttendanceCreditService).grant(1L);
    }

    @Test
    @DisplayName("비밀번호 불일치에는 일별 접속 크레딧을 지급하지 않는다")
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

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());

        verify(loginRateLimiter).recordFailure(EMAIL);

        verifyNoInteractions(dailyAttendanceCreditService);
    }

    @Test
    @DisplayName("로그인 세션 저장 실패에는 일별 접속 크레딧을 지급하지 않는다")
    void login_doesNotGrantDailyCreditWhenSessionSaveFails() {
        stubSuccessfulLogin();

        LocalDateTime authenticatedAt = LocalDateTime.ofInstant(NOW, SEOUL);

        doThrow(new DataAccessResourceFailureException("인증 세션 저장 실패")
        ).when(loginSessionService)
                .saveLoginSession(
                        user,
                        null,
                        REFRESH_TOKEN_HASH,
                        authenticatedAt,
                        authenticatedAt.plusDays(14)
                );

        assertThrows(
                DataAccessResourceFailureException.class,
                () -> loginService.login(
                        request(),
                        null
                )
        );

        verifyNoInteractions(
                dailyAttendanceCreditService
        );
    }

    @Test
    @DisplayName("출석 보상 장애가 발생해도 저장된 로그인 세션과 토큰을 반환한다")
    void login_preservesAuthenticationWhenAttendanceFails() {
        stubSuccessfulLogin();
        doThrow(new DataAccessResourceFailureException("출석 DB 장애"))
                .when(dailyAttendanceCreditService).grant(1L);
        LoginResult result = loginService.login(request(), null);
        assertEquals(ACCESS_TOKEN, result.accessToken());
        verify(dailyAttendanceCreditService).grant(1L);
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

        when(refreshTokenProvider.generate()).thenReturn(REFRESH_TOKEN);

        when(refreshTokenProvider.hash(REFRESH_TOKEN)).thenReturn(REFRESH_TOKEN_HASH);
    }

    private LoginRequest request() {
        return new LoginRequest(EMAIL, RAW_PASSWORD);
    }
}
