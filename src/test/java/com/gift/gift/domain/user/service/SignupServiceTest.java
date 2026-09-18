package com.gift.gift.domain.user.service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.SignupPolicyViolationException;
import com.gift.gift.domain.user.exception.SignupTermsConfigurationException;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private TermRepository termRepository;

    @Mock
    private TermConsentRepository consentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    private SignupService service;
    private Term term;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-09-18T03:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        service = new SignupService(
                userRepository, termRepository, consentRepository,
                passwordEncoder, clock
        );

        term = new Term("TERMS", 3, "제목", "본문", true);
        ReflectionTestUtils.setField(term, "id", 1L);
    }

    @ParameterizedTest
    @CsvSource({
            "2026-09-19, OUT_OF_RANGE",
            "1906-09-17, OUT_OF_RANGE",
            "2012-09-19, AGE_REQUIREMENT_NOT_MET"
    })
    @DisplayName("가입 정책 위반은 전용 예외에 필드 상세를 담는다")
    void signup_rejectsBirthPolicyViolation(String birth, String reason) {
        SignupPolicyViolationException exception = assertThrows(
                SignupPolicyViolationException.class,
                () -> service.signup(request(birth, 3, true))
        );

        assertEquals("birth", exception.getDetails().getFirst().field());
        assertEquals(reason, exception.getDetails().getFirst().reason().name());

        verifyNoInteractions(
                userRepository, termRepository, consentRepository, passwordEncoder
        );
    }

    @ParameterizedTest
    @CsvSource({"2012-09-18", "1906-09-18"})
    @DisplayName("정확히 만 14세와 120년 경계는 허용한다")
    void signup_acceptsBirthPolicyBoundary(String birth) {
        stubSuccessfulPersistence();

        assertEquals(10L, service.signup(request(birth, 3, true)).userId());
    }

    @Test
    @DisplayName("약관 버전 불일치는 전용 업무 코드로 처리한다")
    void signup_rejectsOutdatedVersion() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request("2000-01-01", 2, true))
        );

        assertEquals(ErrorCode.INVALID_TERM_VERSION, exception.getErrorCode());
        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("필수 약관 미동의를 거부한다")
    void signup_rejectsRequiredTermNotAgreed() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request("2000-01-01", 3, false))
        );

        assertEquals(ErrorCode.REQUIRED_TERMS_NOT_AGREED, exception.getErrorCode());
    }

    @Test
    @DisplayName("필수 약관 누락을 거부한다")
    void signup_rejectsMissingRequiredConsent() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));

        SignupRequest request = new SignupRequest(
                "김선물", "2000-01-01", "user@example.com", "Password1!", List.of()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request)
        );

        assertEquals(ErrorCode.REQUIRED_TERMS_NOT_AGREED, exception.getErrorCode());
    }

    @Test
    @DisplayName("현재 필수 약관 설정 부재는 서버 오류로 구분한다")
    void signup_rejectsMissingTermsConfiguration() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of());

        assertThrows(
                SignupTermsConfigurationException.class,
                () -> service.signup(request("2000-01-01", 3, true))
        );

        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("중복 이메일은 해싱 전에 거부한다")
    void signup_rejectsExistingEmailBeforeEncoding() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request("2000-01-01", 3, true))
        );

        assertEquals(ErrorCode.EMAIL_ALREADY_IN_USE, exception.getErrorCode());
        verifyNoInteractions(passwordEncoder, consentRepository);
    }

    @Test
    @DisplayName("확인되지 않은 DB 무결성 오류를 이메일 중복으로 바꾸지 않는다")
    void signup_doesNotMisclassifyOtherIntegrityViolation() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded-value");

        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("other integrity violation");

        when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertSame(failure, assertThrows(
                DataIntegrityViolationException.class,
                () -> service.signup(request("2000-01-01", 3, true))
        ));
    }

    private void stubSuccessfulPersistence() {
        when(termRepository.findCurrentRequiredTerms()).thenReturn(List.of(term));
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded-value");

        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            assertEquals("encoded-value", user.getPasswordHash());
            return user;
        });
    }

    private SignupRequest request(String birth, int version, boolean agreed) {
        return new SignupRequest(
                "김선물", birth, "USER@example.com", "Password1!",
                List.of(new SignupTermConsentRequest(1L, version, agreed))
        );
    }
}
