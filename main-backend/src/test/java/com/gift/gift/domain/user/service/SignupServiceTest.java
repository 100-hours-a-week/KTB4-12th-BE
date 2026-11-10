package com.gift.gift.domain.user.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.auth.exception.AuthErrorCode;
import com.gift.gift.domain.auth.exception.AuthException;
import com.gift.gift.domain.auth.service.EmailVerificationService;
import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.dto.response.SignupResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.SignupTermsConfigurationException;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final String REGISTRATION_TOKEN = "registration-token";

    @Mock
    private UserRepository userRepository;

    @Mock
    private TermRepository termRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private SignupTransactionService transactionService;

    private SignupService service;
    private Term requiredTerm;
    private Term optionalTerm;

    @BeforeEach
    void setUp() {
        service = new SignupService(
                userRepository,
                termRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );

        requiredTerm = term(1L, "TERMS", 3, true);
        optionalTerm = term(2L, "MARKETING", 2, false);
    }

    @Test
    @DisplayName("필수 약관 버전이 다르면 해싱과 저장을 실행하지 않는다")
    void signup_rejectsOutdatedRequiredTermVersion() {
        stubCurrentTerms();

        UserException exception = assertThrows(
                UserException.class,
                () -> service.signup(request(List.of(
                        consent(1L, 2, true)
                )))
        );

        assertEquals(ErrorCode.INVALID_TERM_VERSION, exception.getErrorCode());

        verifyNoInteractions(
                userRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );
    }

    @Test
    @DisplayName("선택 약관 버전이 달라도 해싱과 저장을 실행하지 않는다")
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

        verifyNoInteractions(
                userRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );
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

        verifyNoInteractions(
                userRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );
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

        verifyNoInteractions(
                userRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );
    }

    @Test
    @DisplayName("현재 필수 약관이 없으면 서버 설정 오류로 처리한다")
    void signup_rejectsMissingRequiredTermsConfiguration() {
        when(termRepository.findCurrentTerms())
                .thenReturn(List.of(optionalTerm));

        assertThrows(
                SignupTermsConfigurationException.class,
                () -> service.signup(request(List.of(
                        consent(2L, 2, false)
                )))
        );

        verifyNoInteractions(
                userRepository,
                passwordEncoder,
                emailVerificationService,
                transactionService
        );
    }

    @Test
    @DisplayName("유효하지 않은 가입용 토큰은 해싱과 저장 전에 거부한다")
    void signup_rejectsInvalidRegistrationToken() {
        stubCurrentTerms();

        SignupRequest request = request(List.of(
                consent(1L, 3, true)
        ));

        doThrow(new AuthException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID))
                .when(emailVerificationService)
                .consumeRegistrationToken(
                        request.email(),
                        request.emailVerificationToken()
                );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.signup(request)
        );

        assertEquals(ErrorCode.INVALID_REQUEST, exception.getErrorCode());
        verify(userRepository).existsByEmail(request.email());
        verify(emailVerificationService).consumeRegistrationToken(
                request.email(),
                request.emailVerificationToken()
        );
        verifyNoInteractions(passwordEncoder, transactionService);
    }

    @Test
    @DisplayName("선택 약관 미동의는 회원가입을 차단하지 않는다")
    void signup_allowsOptionalTermNotAgreed() {
        stubCurrentTerms();
        stubSuccessfulSignup();

        SignupRequest request = request(List.of(
                consent(1L, 3, true),
                consent(2L, 2, false)
        ));

        SignupResponse response = service.signup(request);

        assertEquals(10L, response.userId());
    }

    @Test
    @DisplayName("선택 약관 누락은 회원가입을 차단하지 않는다")
    void signup_allowsOmittedOptionalConsent() {
        stubCurrentTerms();
        stubSuccessfulSignup();

        SignupRequest request = request(List.of(
                consent(1L, 3, true)
        ));

        SignupResponse response = service.signup(request);

        assertEquals(10L, response.userId());
    }

    @Test
    @DisplayName("중복 이메일은 비밀번호 해싱 전에 거부한다")
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

        verifyNoInteractions(passwordEncoder, transactionService);
        verifyNoInteractions(emailVerificationService);
    }

    @Test
    @DisplayName("이메일 인증 후 중복 확인, 해싱, 저장 순서로 처리한다")
    void signup_validatesEmailBeforeEncodingAndPersistence() {
        stubCurrentTerms();

        when(userRepository.existsByEmail("user@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Password1!")).thenReturn(PASSWORD_HASH);

        SignupRequest request = request(List.of(
                consent(1L, 3, true),
                consent(2L, 2, false)
        ));

        User savedUser = user(10L, request, PASSWORD_HASH);

        when(transactionService.create(
                request,
                LocalDate.of(2000, 1, 1),
                PASSWORD_HASH
        )).thenReturn(savedUser);

        SignupResponse response = service.signup(request);

        assertEquals(10L, response.userId());

        InOrder order = inOrder(
                userRepository,
                emailVerificationService,
                passwordEncoder,
                transactionService
        );

        order.verify(userRepository).existsByEmail("user@example.com");
        order.verify(emailVerificationService).consumeRegistrationToken(
                request.email(),
                request.emailVerificationToken()
        );
        order.verify(passwordEncoder).encode("Password1!");
        order.verify(transactionService).create(
                request,
                LocalDate.of(2000, 1, 1),
                PASSWORD_HASH
        );
    }

    private void stubCurrentTerms() {
        when(termRepository.findCurrentTerms())
                .thenReturn(List.of(requiredTerm, optionalTerm));
    }

    private void stubSuccessfulSignup() {
        when(userRepository.existsByEmail(
                "user@example.com"
        )).thenReturn(false);

        when(passwordEncoder.encode("Password1!")).thenReturn(PASSWORD_HASH);

        when(transactionService.create(
                org.mockito.ArgumentMatchers.any(SignupRequest.class),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2000, 1, 1)),
                org.mockito.ArgumentMatchers.eq(PASSWORD_HASH)
        )).thenAnswer(invocation -> {
            SignupRequest request = invocation.getArgument(0);

            return user(10L, request, PASSWORD_HASH);
        });
    }

    private User user(Long id, SignupRequest request, String passwordHash) {

        User user = new User(
                request.email(),
                passwordHash,
                request.name(),
                LocalDate.parse(request.birth())
        );

        ReflectionTestUtils.setField(user, "id", id);

        return user;
    }

    private Term term(Long id, String code, int version, boolean required) {

        Term term = new Term(code, version, "제목", "본문", required);

        ReflectionTestUtils.setField(term, "id", id);

        return term;
    }

    private SignupTermConsentRequest consent(Long termId, int version, boolean agreed) {
        return new SignupTermConsentRequest(
                termId,
                version,
                agreed
        );
    }

    private SignupRequest request(List<SignupTermConsentRequest> consents) {
        return new SignupRequest(
                "김선물",
                "2000-01-01",
                "USER@example.com",
                REGISTRATION_TOKEN,
                "Password1!",
                consents
        );
    }
}
