package com.gift.gift.domain.user.service;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.coupon.service.CouponInitializationService;
import com.gift.gift.domain.credit.service.SignupCreditService;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.request.SignupTermConsentRequest;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.entity.TermConsent;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.exception.UserException;
import com.gift.gift.domain.user.repository.TermConsentRepository;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SignupTransactionServiceTest {

    private static final String PASSWORD_HASH = "$2a$10$" + "a".repeat(53);
    private static final String REGISTRATION_TOKEN = "registration-token";

    @Mock
    private UserRepository userRepository;

    @Mock
    private TermRepository termRepository;

    @Mock
    private TermConsentRepository consentRepository;

    @Mock
    private SignupCreditService signupCreditService;

    @Mock
    private RecipientProfileRepository profileRepository;

    @Mock
    private CouponInitializationService couponInitializationService;

    @Captor
    private ArgumentCaptor<User> userCaptor;

    @Captor
    private ArgumentCaptor<List<TermConsent>> consentCaptor;

    private SignupTransactionService service;
    private Term requiredTerm;
    private Term optionalTerm;

    @BeforeEach
    void setUp() {
        service = new SignupTransactionService(
                userRepository,
                termRepository,
                consentRepository,
                signupCreditService,
                profileRepository,
                couponInitializationService
        );

        requiredTerm = term(1L, "TERMS", 3, true);
        optionalTerm = term(2L, "MARKETING", 2, false);
    }

    @Test
    @DisplayName("회원과 제출된 약관을 저장하고 최초 크레딧을 지급한다")
    void create_persistsUserConsentsAndInitialCredit() {
        SignupRequest request = request(List.of(
                consent(1L, 3, true),
                consent(2L, 2, false)
        ));

        when(userRepository.saveAndFlush(any(User.class)
        )).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            return user;
        });

        when(termRepository.getReferenceById(1L)).thenReturn(requiredTerm);
        when(termRepository.getReferenceById(2L)).thenReturn(optionalTerm);

        User result = service.create(
                request,
                LocalDate.of(2000, 1, 1),
                PASSWORD_HASH
        );

        verify(userRepository).saveAndFlush(userCaptor.capture());
        verify(consentRepository).saveAllAndFlush(consentCaptor.capture());
        verify(signupCreditService).grant(10L);
        verify(couponInitializationService).initialize(10L);

        ArgumentCaptor<RecipientProfile> profileCaptor =
                ArgumentCaptor.forClass(RecipientProfile.class);
        verify(profileRepository).save(profileCaptor.capture());
        assertSame(result, profileCaptor.getValue().getRecipient());
        assertEquals(0L, profileCaptor.getValue().getSourceVersion());
        assertEquals(0L, profileCaptor.getValue().getAnalyzedSourceVersion());

        User savedUser = userCaptor.getValue();

        assertEquals(10L, result.getId());
        assertEquals("user@example.com", savedUser.getEmail());
        assertEquals(PASSWORD_HASH, savedUser.getPasswordHash());
        assertEquals("김선물", savedUser.getName());
        assertEquals(LocalDate.of(2000, 1, 1), savedUser.getBirth());
        assertNotNull(savedUser.getEmailVerifiedAt());

        List<TermConsent> savedConsents = consentCaptor.getValue();

        assertEquals(2, savedConsents.size());

        TermConsent requiredConsent = findConsent(savedConsents, 1L);
        TermConsent optionalConsent = findConsent(savedConsents, 2L);

        assertSame(result, requiredConsent.getUser());
        assertSame(requiredTerm, requiredConsent.getTerm());
        assertTrue(requiredConsent.isAgreed());

        assertSame(result, optionalConsent.getUser());
        assertSame(optionalTerm, optionalConsent.getTerm());
        assertEquals(false, optionalConsent.isAgreed());
    }

    @Test
    @DisplayName("선택 약관을 생략하면 제출된 필수 약관만 저장한다")
    void create_persistsOnlySubmittedConsents() {
        SignupRequest request = request(List.of(
                consent(1L, 3, true)
        ));

        when(userRepository.saveAndFlush(
                any(User.class)
        )).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            ReflectionTestUtils.setField(user, "id", 10L);
            return user;
        });

        when(termRepository.getReferenceById(1L))
                .thenReturn(requiredTerm);

        service.create(
                request,
                LocalDate.of(2000, 1, 1),
                PASSWORD_HASH
        );

        verify(consentRepository).saveAllAndFlush(consentCaptor.capture());

        List<TermConsent> savedConsents = consentCaptor.getValue();

        assertEquals(1, savedConsents.size());
        assertEquals(1L, savedConsents.getFirst().getTerm().getId());
        assertTrue(savedConsents.getFirst().isAgreed());

        verify(signupCreditService).grant(10L);
        verify(couponInitializationService).initialize(10L);
    }

    @Test
    @DisplayName("이메일 UNIQUE 충돌은 중복 이메일 업무 오류로 변환한다")
    void create_convertsEmailUniqueViolation() {
        DataIntegrityViolationException failure =
                uniqueViolation("uk_users_email", 1062);

        when(userRepository.saveAndFlush(
                any(User.class)
        )).thenThrow(failure);

        UserException exception = assertThrows(
                UserException.class,
                () -> service.create(
                        request(List.of(consent(1L, 3, true))),
                        LocalDate.of(2000, 1, 1),
                        PASSWORD_HASH
                )
        );

        assertEquals(ErrorCode.EMAIL_ALREADY_IN_USE, exception.getErrorCode());

        verifyNoInteractions(
                termRepository, consentRepository, signupCreditService, couponInitializationService
        );
    }

    @Test
    @DisplayName("다른 UNIQUE 제약 오류는 이메일 중복으로 변환하지 않는다")
    void create_doesNotConvertOtherUniqueViolation() {
        DataIntegrityViolationException failure =
                uniqueViolation("uk_other_constraint", 1062);

        when(userRepository.saveAndFlush(
                any(User.class)
        )).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.create(
                        request(List.of(consent(1L, 3, true))),
                        LocalDate.of(2000, 1, 1),
                        PASSWORD_HASH
                )
        );

        assertSame(failure, thrown);

        verifyNoInteractions(
                termRepository, consentRepository, signupCreditService, couponInitializationService
        );
    }

    @Test
    @DisplayName("같은 제약 이름이어도 MySQL 중복 오류가 아니면 변환하지 않는다")
    void create_checksMysqlDuplicateErrorCode() {
        DataIntegrityViolationException failure =
                uniqueViolation("uk_users_email", 1452);

        when(userRepository.saveAndFlush(
                any(User.class)
        )).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.create(
                        request(List.of(consent(1L, 3, true))),
                        LocalDate.of(2000, 1, 1),
                        PASSWORD_HASH
                )
        );

        assertSame(failure, thrown);

        verifyNoInteractions(
                termRepository, consentRepository, signupCreditService, couponInitializationService
        );
    }

    @Test
    @DisplayName("확인되지 않은 무결성 오류는 원래 예외를 전파한다")
    void create_propagatesUnknownIntegrityViolation() {
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("확인되지 않은 무결성 오류");

        when(userRepository.saveAndFlush(
                any(User.class)
        )).thenThrow(failure);

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.create(
                        request(List.of(consent(1L, 3, true))),
                        LocalDate.of(2000, 1, 1),
                        PASSWORD_HASH
                )
        );

        assertSame(failure, thrown);

        verifyNoInteractions(
                termRepository, consentRepository, signupCreditService, couponInitializationService
        );
    }

    private TermConsent findConsent(List<TermConsent> consents, Long termId) {
        return consents.stream()
                .filter(consent ->
                        consent.getTerm().getId().equals(termId))
                .findFirst()
                .orElseThrow();
    }

    private DataIntegrityViolationException uniqueViolation(
            String constraintName,
            int errorCode
    ) {
        SQLException sqlException =
                new SQLException("회원 저장 제약 위반", "23000", errorCode);

        ConstraintViolationException violation =
                new ConstraintViolationException(
                        "회원 저장 제약 위반",
                        sqlException,
                        constraintName
                );

        return new DataIntegrityViolationException(
                "회원을 저장할 수 없습니다.",
                violation
        );
    }

    private Term term(Long id, String code, int version, boolean required) {
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
