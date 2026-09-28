package com.gift.gift.domain.user.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.TermConsent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.SignupTermsConfigurationException;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
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

    @Captor
    private ArgumentCaptor<List<TermConsent>> consentCaptor;

    private SignupService service;
    private Term requiredTerm;
    private Term optionalTerm;

    @BeforeEach
    void setUp() {
        service = new SignupService(
                userRepository, termRepository, consentRepository,
                passwordEncoder
        );

        requiredTerm = term(1L, "TERMS", 3, true);
        optionalTerm = term(2L, "MARKETING", 2, false);
    }

    @Test
    @DisplayName("필수 약관 버전 불일치는 전용 업무 코드로 처리한다")
    void signup_rejectsOutdatedRequiredTermVersion() {
        stubCurrentTerms();

        UserException exception = assertThrows(
                UserException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 2, true)
                )))
        );

        assertEquals(ErrorCode.INVALID_TERM_VERSION, exception.getErrorCode());
        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("선택 약관 버전 불일치도 저장 전에 거부한다")
    void signup_rejectsOutdatedOptionalTermVersion() {
        stubCurrentTerms();

        UserException exception = assertThrows(
                UserException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 3, true),
                        consent(2L, 1, false)
                )))
        );

        assertEquals(ErrorCode.INVALID_TERM_VERSION, exception.getErrorCode());
        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("필수 약관 미동의를 거부한다")
    void signup_rejectsRequiredTermNotAgreed() {
        stubCurrentTerms();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 3, false)
                )))
        );

        assertEquals(ErrorCode.REQUIRED_TERMS_NOT_AGREED, exception.getErrorCode());
        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("필수 약관 누락을 거부한다")
    void signup_rejectsMissingRequiredConsent() {
        stubCurrentTerms();

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request(List.of(
                        consent(2L, 2, true)
                )))
        );

        assertEquals(ErrorCode.REQUIRED_TERMS_NOT_AGREED, exception.getErrorCode());
        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("현재 약관 중 필수 약관이 없으면 서버 설정 오류로 처리한다")
    void signup_rejectsMissingRequiredTermsConfiguration() {
        when(termRepository.findCurrentTerms())
                .thenReturn(List.of(optionalTerm));

        assertThrows(
                SignupTermsConfigurationException.class,
                () -> service.signup(request(List.of(
                        consent(2L, 2, false)
                )))
        );

        verifyNoInteractions(userRepository, consentRepository, passwordEncoder);
    }

    @Test
    @DisplayName("선택 약관 동의를 선택하면 true로 저장한다")
    void signup_savesOptionalConsentAsAgreed() {
        stubSuccessfulPersistence();

        service.signup(request(List.of(
                consent(1L, 3, true),
                consent(2L, 2, true)
        )));

        List<TermConsent> savedConsents = captureSavedConsents();

        assertEquals(2, savedConsents.size());
        assertTrue(findConsent(savedConsents, 2L).isAgreed());
    }

    @Test
    @DisplayName("선택 약관에 동의하지 않으면 false로 저장한다")
    void signup_savesOptionalConsentAsNotAgreed() {
        stubSuccessfulPersistence();

        service.signup(request(List.of(
                consent(1L, 3, true),
                consent(2L, 2, false)
        )));

        List<TermConsent> savedConsents = captureSavedConsents();

        assertEquals(2, savedConsents.size());
        assertFalse(findConsent(savedConsents, 2L).isAgreed());
    }

    @Test
    @DisplayName("선택 약관을 누락하면 필수 약관 동의만 저장한다")
    void signup_allowsOmittedOptionalConsent() {
        stubSuccessfulPersistence();

        service.signup(request(List.of(
                consent(1L, 3, true)
        )));

        List<TermConsent> savedConsents = captureSavedConsents();

        assertEquals(1, savedConsents.size());
        assertEquals(1L, savedConsents.getFirst().getTerm().getId());
        assertTrue(savedConsents.getFirst().isAgreed());
    }

    @Test
    @DisplayName("중복 이메일은 해싱 전에 거부한다")
    void signup_rejectsExistingEmailBeforeEncoding() {
        stubCurrentTerms();
        when(userRepository.existsByEmail("user@example.com")).thenReturn(true);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 3, true)
                )))
        );

        assertEquals(ErrorCode.EMAIL_ALREADY_IN_USE, exception.getErrorCode());
        verifyNoInteractions(passwordEncoder, consentRepository);
    }

    @Test
    @DisplayName("확인되지 않은 DB 무결성 오류를 이메일 중복으로 바꾸지 않는다")
    void signup_doesNotMisclassifyOtherIntegrityViolation() {
        stubCurrentTerms();
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded-value");

        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("other integrity violation");

        when(userRepository.saveAndFlush(any(User.class))).thenThrow(failure);

        assertSame(failure, assertThrows(
                DataIntegrityViolationException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 3, true)
                )))
        ));
    }

    private void stubCurrentTerms() {
        when(termRepository.findCurrentTerms())
                .thenReturn(List.of(requiredTerm, optionalTerm));
    }

    private void stubSuccessfulPersistence() {
        stubCurrentTerms();
        when(passwordEncoder.encode("Password1!")).thenReturn("encoded-value");

        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            assertEquals("encoded-value", user.getPasswordHash());
            return user;
        });
    }

    private List<TermConsent> captureSavedConsents() {
        verify(consentRepository).saveAllAndFlush(consentCaptor.capture());
        return consentCaptor.getValue();
    }

    private TermConsent findConsent(
            List<TermConsent> consents,
            Long termId
    ) {
        return consents.stream()
                .filter(consent -> consent.getTerm().getId().equals(termId))
                .findFirst()
                .orElseThrow();
    }

    private Term term(
            Long id,
            String code,
            int version,
            boolean required
    ) {
        Term term = new Term(code, version, "제목", "본문", required);
        ReflectionTestUtils.setField(term, "id", id);
        return term;
    }

    private SignupTermConsentRequest consent(
            Long termId,
            int version,
            boolean agreed
    ) {
        return new SignupTermConsentRequest(termId, version, agreed);
    }

    private SignupRequest request(
            List<SignupTermConsentRequest> consents
    ) {
        return new SignupRequest(
                "김선물",
                "2000-01-01",
                "USER@example.com",
                "Password1!",
                consents
        );
    }
}
