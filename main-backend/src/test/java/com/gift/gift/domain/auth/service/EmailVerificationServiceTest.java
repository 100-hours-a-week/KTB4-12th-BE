package com.gift.gift.domain.auth.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.support.RateLimitIdentifierHasher;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    private static final String EMAIL = "user@example.com";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String EMAIL_HASH = "email-hash";
    private static final String IP_HASH = "ip-hash";
    private static final String CODE = "123456";
    private static final String REGISTRATION_TOKEN = "signup-token";

    @Mock
    private UserRepository userRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private JavaMailSender mailSender;

    @Mock
    private RateLimitIdentifierHasher identifierHasher;

    private final List<String> executedKeys = new ArrayList<>();
    private final AtomicReference<String> savedCode = new AtomicReference<>();
    private final AtomicReference<Object[]> scriptArguments = new AtomicReference<>();

    private EmailVerificationService emailVerificationService;

    @BeforeEach
    void setUp() {
        emailVerificationService = new EmailVerificationService(
                userRepository,
                redisTemplate,
                mailSender,
                identifierHasher
        );
        ReflectionTestUtils.setField(
                emailVerificationService,
                "fromAddress",
                "no-reply@example.com"
        );
    }

    @Test
    @DisplayName("발급 성공은 요청 카운터와 인증 코드를 Redis에 저장하고 SMTP 메일에 6자리 코드를 담는다")
    void sendVerificationCode_storesCountersAndSendsSixDigitCode() {
        givenIssuanceIdentity();
        givenCooldown();
        stubIssuanceScripts(1L);

        emailVerificationService.sendVerificationCode(EMAIL, CLIENT_IP);

        assertEquals(List.of(
                "gift:auth:email-verification:request:email:" + EMAIL_HASH,
                "gift:auth:email-verification:request:ip:" + IP_HASH,
                "gift:auth:email-verification:state:" + EMAIL_HASH
        ), executedKeys);
        assertNotNull(savedCode.get());
        assertTrue(savedCode.get().matches("^[0-9]{6}$"));

        ArgumentCaptor<SimpleMailMessage> messageCaptor =
                ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(messageCaptor.capture());

        SimpleMailMessage message = messageCaptor.getValue();
        assertEquals("no-reply@example.com", message.getFrom());
        assertEquals(EMAIL, message.getTo()[0]);
        assertTrue(message.getText().contains("인증번호는 " + savedCode.get()));
        verify(valueOperations).setIfAbsent(
                "gift:auth:email-verification:cooldown:" + EMAIL_HASH,
                "1",
                Duration.ofSeconds(60)
        );
    }

    @Test
    @DisplayName("시간당 발급 한도를 넘으면 SMTP 발송 없이 429 오류를 반환한다")
    void sendVerificationCode_doesNotSendMailWhenHourlyLimitIsExceeded() {
        givenIssuanceIdentity();
        doAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            executedKeys.add(keys.get(0));
            return keys.get(0).contains(":request:email:") ? 6L : 1L;
        }).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<String>anyList(),
                any()
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.sendVerificationCode(
                        EMAIL,
                        CLIENT_IP
                )
        );

        assertEquals(ErrorCode.TOO_MANY_REQUESTS, exception.getErrorCode());
        assertEquals(2, executedKeys.size());
        verifyNoInteractions(valueOperations, mailSender);
    }

    @Test
    @DisplayName("Redis 장애로 인증번호 발급 상태를 저장하지 못하면 503 오류를 반환하고 메일을 보내지 않는다")
    void sendVerificationCode_mapsRedisFailureToServiceUnavailable() {
        givenIssuanceIdentity();
        doAnswer(invocation -> {
            throw new DataAccessResourceFailureException("Redis unavailable");
        }).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<String>anyList(),
                any()
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.sendVerificationCode(
                        EMAIL,
                        CLIENT_IP
                )
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );
        verifyNoInteractions(mailSender);
    }

    @Test
    @DisplayName("SMTP 발송 장애는 503 오류로 변환한다")
    void sendVerificationCode_mapsSmtpFailureToServiceUnavailable() {
        givenIssuanceIdentity();
        givenCooldown();
        stubIssuanceScripts(1L);
        doThrow(new MailSendException("SMTP unavailable"))
                .when(mailSender)
                .send(any(SimpleMailMessage.class));

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.sendVerificationCode(
                        EMAIL,
                        CLIENT_IP
                )
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );
        verify(mailSender).send(any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("인증번호 검증에 성공하면 가입용 토큰을 발급한다")
    void verifyCode_returnsRegistrationTokenWhenRedisVerifiesCode() {
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        stubVerificationScript("VERIFIED", 4);

        String token = emailVerificationService.verifyCode(EMAIL, CODE);

        assertNotNull(token);
        assertTrue(token.matches("^[A-Za-z0-9_-]{43}$"));
        assertNotEquals(CODE, token);
        assertEquals(
                "gift:auth:email-verification:state:" + EMAIL_HASH,
                executedKeys.getFirst()
        );
        assertEquals(CODE, scriptArguments.get()[0]);
        assertTrue(scriptArguments.get()[1].toString()
                .matches("^[0-9a-f]{64}$"));
        assertEquals("600", scriptArguments.get()[2]);
    }

    @Test
    @DisplayName("Redis가 인증번호를 거절하면 INVALID_REQUEST를 반환한다")
    void verifyCode_rejectsCodeWhenRedisDoesNotVerifyIt() {
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        stubVerificationScript("INVALID", 4);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.verifyCode(EMAIL, CODE)
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verify(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    @DisplayName("가입용 토큰은 일치할 때 Redis에서 소비한다")
    void consumeRegistrationToken_deletesMatchingToken() {
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        stubVerificationScript("CONSUMED", 1);

        emailVerificationService.consumeRegistrationToken(
                EMAIL,
                REGISTRATION_TOKEN
        );

        assertEquals(
                "gift:auth:email-verification:state:" + EMAIL_HASH,
                executedKeys.getFirst()
        );
        assertTrue(scriptArguments.get()[0].toString()
                .matches("^[0-9a-f]{64}$"));
        assertNotEquals(REGISTRATION_TOKEN, scriptArguments.get()[0]);
    }

    @Test
    @DisplayName("이미 소비됐거나 이메일에 묶이지 않은 가입용 토큰은 거절한다")
    void consumeRegistrationToken_rejectsInvalidToken() {
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        stubVerificationScript("INVALID", 1);

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.consumeRegistrationToken(
                        EMAIL,
                        REGISTRATION_TOKEN
                )
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verify(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                ArgumentMatchers.<String>anyList(),
                any()
        );
    }

    @Test
    @DisplayName("가입 토큰 확인 중 Redis 장애는 503 오류로 변환한다")
    void consumeRegistrationToken_mapsRedisFailureToServiceUnavailable() {
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        doAnswer(invocation -> {
            throw new DataAccessResourceFailureException("Redis unavailable");
        }).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                ArgumentMatchers.<String>anyList(),
                any()
        );

        AuthException exception = assertThrows(
                AuthException.class,
                () -> emailVerificationService.consumeRegistrationToken(
                        EMAIL,
                        REGISTRATION_TOKEN
                )
        );

        assertEquals(
                ErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE,
                exception.getErrorCode()
        );
    }

    private void givenIssuanceIdentity() {
        when(userRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(identifierHasher.hashEmail(EMAIL)).thenReturn(EMAIL_HASH);
        when(identifierHasher.hashIp(CLIENT_IP)).thenReturn(IP_HASH);
    }

    private void givenCooldown() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(
                "gift:auth:email-verification:cooldown:" + EMAIL_HASH,
                "1",
                Duration.ofSeconds(60)
        )).thenReturn(true);
    }

    private void stubIssuanceScripts(Long result) {
        Answer<Long> answer = invocation -> {
            Object[] arguments = invocation.getArguments();
            List<String> keys = invocation.getArgument(1);
            String key = keys.getFirst();
            executedKeys.add(key);

            if (key.contains(":state:")) {
                savedCode.set(arguments[2].toString());
            }

            return result;
        };

        doAnswer(answer).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<String>anyList(),
                any()
        );
        doAnswer(answer).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<Long>>any(),
                ArgumentMatchers.<String>anyList(),
                any(),
                any()
        );
    }

    private void stubVerificationScript(String result, int argumentCount) {
        Answer<String> answer = invocation -> {
            Object[] arguments = invocation.getArguments();
            List<String> keys = invocation.getArgument(1);
            executedKeys.add(keys.getFirst());
            scriptArguments.set(Arrays.copyOfRange(arguments, 2, arguments.length));
            return result;
        };

        if (argumentCount == 1) {
            doAnswer(answer).when(redisTemplate).execute(
                    ArgumentMatchers.<RedisScript<String>>any(),
                    ArgumentMatchers.<String>anyList(),
                    any()
            );
            return;
        }

        doAnswer(answer).when(redisTemplate).execute(
                ArgumentMatchers.<RedisScript<String>>any(),
                ArgumentMatchers.<String>anyList(),
                any(),
                any(),
                any(),
                any()
        );
    }
}
