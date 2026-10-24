package com.gift.gift.domain.auth.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import jakarta.mail.internet.MimeMessage;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.support.RateLimitIdentifierHasher;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.support.EmailNormalizer;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String KEY_PREFIX = "gift:auth:email-verification";

    private static final int CODE_TTL_SECONDS = 300;
    private static final int TOKEN_TTL_SECONDS = 600;
    private static final int RATE_LIMIT_TTL_SECONDS = 3600;
    private static final int RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_REQUESTS_PER_HOUR = 5;
    private static final int MAX_FAILED_ATTEMPTS = 5;

    private static final DefaultRedisScript<Long> INCREMENT_COUNTER_SCRIPT =
            new DefaultRedisScript<>("""
                    local count = redis.call('INCR', KEYS[1])
                    if count == 1 then
                      redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1]))
                    end
                    return count
                    """, Long.class);

    private static final DefaultRedisScript<Long> SAVE_CODE_SCRIPT =
            new DefaultRedisScript<>("""
                    redis.call(
                      'HSET',
                      KEYS[1],
                      'code',
                      ARGV[1],
                      'failedAttempts',
                      '0'
                    )
                    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))
                    return 1
                    """, Long.class);

    private static final DefaultRedisScript<String> VERIFY_CODE_SCRIPT =
            new DefaultRedisScript<>("""
                    local actual = redis.call('HGET', KEYS[1], 'code')
                    if not actual then
                      return 'INVALID'
                    end

                    if actual ~= ARGV[1] then
                      local attempts = redis.call(
                        'HINCRBY',
                        KEYS[1],
                        'failedAttempts',
                        1
                      )
                      if attempts >= tonumber(ARGV[4]) then
                        redis.call('DEL', KEYS[1])
                      end
                      return 'INVALID'
                    end

                    redis.call(
                      'HDEL',
                      KEYS[1],
                      'code',
                      'failedAttempts'
                    )
                    redis.call(
                      'HSET',
                      KEYS[1],
                      'tokenHash',
                      ARGV[2]
                    )
                    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
                    return 'VERIFIED'
                    """, String.class);

    private static final DefaultRedisScript<String> VALIDATE_TOKEN_SCRIPT =
            new DefaultRedisScript<>("""
                    local tokenHash = redis.call(
                      'HGET',
                      KEYS[1],
                      'tokenHash'
                    )
                    if tokenHash and tokenHash == ARGV[1] then
                      return 'VALID'
                    end
                    return 'INVALID'
                    """, String.class);

    private final UserRepository userRepository;
    private final StringRedisTemplate redisTemplate;
    private final JavaMailSender mailSender;
    private final RateLimitIdentifierHasher identifierHasher;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.email.from-address}")
    private String fromAddress;

    public void sendVerificationCode(String email, String clientIp) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        try {
            if (userRepository.existsByEmail(normalizedEmail)) {
                throw new UserException(UserErrorCode.EMAIL_ALREADY_IN_USE);
            }
        } catch (DataAccessException exception) {
            throw storageUnavailable("이메일 인증 요청에서 회원 정보를 조회하지 못했습니다.", exception);
        }

        String emailHash = identifierHasher.hashEmail(normalizedEmail);
        String ipHash = identifierHasher.hashIp(clientIp);
        String code = generateCode();

        try {
            long emailCount = incrementCounter(
                    emailRequestKey(emailHash)
            );
            long ipCount = incrementCounter(
                    ipRequestKey(ipHash)
            );

            if (emailCount > MAX_REQUESTS_PER_HOUR || ipCount > MAX_REQUESTS_PER_HOUR) {
                throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_RATE_LIMIT_EXCEEDED);
            }

            Boolean cooldownReserved = redisTemplate.opsForValue()
                    .setIfAbsent(
                            cooldownKey(emailHash),
                            "1",
                            Duration.ofSeconds(RESEND_COOLDOWN_SECONDS)
                    );

            if (!Boolean.TRUE.equals(cooldownReserved)) {
                throw new AuthException(AuthErrorCode.EMAIL_VERIFICATION_RATE_LIMIT_EXCEEDED);
            }

            Long saved = redisTemplate.execute(
                    SAVE_CODE_SCRIPT,
                    List.of(challengeKey(emailHash)),
                    code,
                    Integer.toString(CODE_TTL_SECONDS)
            );

            if (!Long.valueOf(1).equals(saved)) {
                throw new AuthException(
                        AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
                );
            }
        } catch (DataAccessException exception) {
            throw storageUnavailable("이메일 인증 상태를 저장하지 못했습니다.", exception);
        }

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromAddress);
            message.setTo(normalizedEmail);
            message.setSubject("이메일 인증번호");
            message.setText(
                    "인증번호는 " + code + "입니다.\n"
                            + "인증번호는 발급 후 5분 동안 유효합니다."
            );
            mailSender.send(message);
        } catch (MailException exception) {
            log.error("이메일 인증번호 발송에 실패했습니다.", exception);
            throw new AuthException(
                    AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE
            );
        }
    }

    public String verifyCode(String email, String code) {
        String normalizedEmail = EmailNormalizer.normalize(email);
        String emailHash = identifierHasher.hashEmail(normalizedEmail);
        String token = generateToken();

        try {
            String result = redisTemplate.execute(
                    VERIFY_CODE_SCRIPT,
                    List.of(challengeKey(emailHash)),
                    code,
                    sha256(token),
                    Integer.toString(TOKEN_TTL_SECONDS),
                    Integer.toString(MAX_FAILED_ATTEMPTS)
            );

            if (!"VERIFIED".equals(result)) {
                throw new AuthException(
                        AuthErrorCode.EMAIL_VERIFICATION_CODE_INVALID
                );
            }

            return token;
        } catch (DataAccessException exception) {
            throw storageUnavailable(
                    "이메일 인증번호를 검증하지 못했습니다.",
                    exception
            );
        }
    }

    public void validateRegistrationToken(
            String email,
            String registrationToken
    ) {
        if (registrationToken == null
                || registrationToken.isBlank()
                || registrationToken.length() > 128) {
            throw invalidRegistrationToken();
        }

        String normalizedEmail = EmailNormalizer.normalize(email);
        String emailHash = identifierHasher.hashEmail(normalizedEmail);

        try {
            String result = redisTemplate.execute(
                    VALIDATE_TOKEN_SCRIPT,
                    List.of(challengeKey(emailHash)),
                    sha256(registrationToken)
            );

            if (!"VALID".equals(result)) {
                throw invalidRegistrationToken();
            }
        } catch (DataAccessException exception) {
            throw storageUnavailable(
                    "회원가입 이메일 인증 상태를 확인하지 못했습니다.",
                    exception
            );
        }
    }

    private long incrementCounter(String key) {
        Long count = redisTemplate.execute(
                INCREMENT_COUNTER_SCRIPT,
                List.of(key),
                Integer.toString(RATE_LIMIT_TTL_SECONDS)
        );

        if (count == null) {
            throw new AuthException(AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE);
        }

        return count;
    }

    private String generateCode() {
        return String.format(
                Locale.ROOT,
                "%06d",
                secureRandom.nextInt(1_000_000)
        );
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("이메일 인증 토큰을 처리하지 못했습니다.", exception);
        }
    }

    private String challengeKey(String emailHash) {
        return KEY_PREFIX + ":state:" + emailHash;
    }

    private String emailRequestKey(String emailHash) {
        return KEY_PREFIX + ":request:email:" + emailHash;
    }

    private String ipRequestKey(String ipHash) {
        return KEY_PREFIX + ":request:ip:" + ipHash;
    }

    private String cooldownKey(String emailHash) {
        return KEY_PREFIX + ":cooldown:" + emailHash;
    }

    private AuthException invalidRegistrationToken() {
        return new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    private AuthException storageUnavailable(String message, DataAccessException exception) {
        log.error(message, exception);
        return new AuthException(AuthErrorCode.AUTHENTICATION_TEMPORARILY_UNAVAILABLE);
    }
}
