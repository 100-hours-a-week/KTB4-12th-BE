package com.gift.gift.domain.user.service;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.user.dto.response.EmailAvailabilityResponse;
import com.gift.gift.domain.user.dto.response.SignupTermsResponse;
import com.gift.gift.domain.user.entity.Term;
import com.gift.gift.domain.user.repository.TermRepository;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SignupPreflightServiceTest {

    @Mock
    private TermRepository termRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SignupPreflightService signupPreflightService;

    @Test
    @DisplayName("현재 필수 약관을 응답 DTO로 변환한다")
    void getSignupTerms_returnsCurrentRequiredTerms() {
        Term term = new Term(
                "PRIVACY_COLLECTION_USE",
                3,
                "개인정보 수집 및 이용 동의서",
                "약관 본문",
                true
        );

        when(termRepository.findCurrentRequiredTerms())
                .thenReturn(List.of(term));

        SignupTermsResponse response =
                signupPreflightService.getSignupTerms();

        assertEquals(1, response.terms().size());
        assertEquals(
                "PRIVACY_COLLECTION_USE",
                response.terms().getFirst().termCode()
        );
        assertEquals(3, response.terms().getFirst().version());
        assertTrue(response.terms().getFirst().isRequired());

        verify(termRepository).findCurrentRequiredTerms();
    }

    @Test
    @DisplayName("현재 필수 약관이 없으면 약관 없음 예외를 발생시킨다")
    void getSignupTerms_fails_whenCurrentRequiredTermsAreMissing() {
        when(termRepository.findCurrentRequiredTerms())
                .thenReturn(List.of());

        BusinessException exception = assertThrows(
                BusinessException.class,
                signupPreflightService::getSignupTerms
        );

        assertEquals(
                ErrorCode.SIGNUP_TERMS_NOT_FOUND,
                exception.getErrorCode()
        );
    }

    @Test
    @DisplayName("사용하지 않는 이메일이면 사용 가능을 반환한다")
    void checkEmailAvailability_returnsTrue_whenEmailDoesNotExist() {
        when(userRepository.existsByEmail("user@example.com"))
                .thenReturn(false);

        EmailAvailabilityResponse response =
                signupPreflightService.checkEmailAvailability(
                        "  User@Example.com  "
                );

        assertTrue(response.available());

        verify(userRepository).existsByEmail("user@example.com");
    }

    @Test
    @DisplayName("이미 사용 중인 이메일이면 사용 불가를 반환한다")
    void checkEmailAvailability_returnsFalse_whenEmailExists() {
        when(userRepository.existsByEmail("user@example.com"))
                .thenReturn(true);

        EmailAvailabilityResponse response =
                signupPreflightService.checkEmailAvailability(
                        "user@example.com"
                );

        assertFalse(response.available());
    }
}
