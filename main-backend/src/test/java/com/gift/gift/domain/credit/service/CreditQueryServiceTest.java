package com.gift.gift.domain.credit.service;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.credit.dto.response.CreditBalanceResponse;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class CreditQueryServiceTest {

    private static final Long USER_ID = 1L;

    private CreditAccountRepository creditAccountRepository;
    private CreditQueryService creditQueryService;

    @BeforeEach
    void setUp() {
        creditAccountRepository = mock(CreditAccountRepository.class);
        creditQueryService = new CreditQueryService(creditAccountRepository
        );
    }

    @Test
    @DisplayName("인증 사용자의 현재 크레딧 잔액을 반환한다")
    void getBalance_returnsCurrentBalance() {
        CreditAccount account = new CreditAccount(USER_ID, 436_000L);

        when(creditAccountRepository.findByUserId(USER_ID)).thenReturn(Optional.of(account));

        CreditBalanceResponse response = creditQueryService.getBalance(USER_ID);

        assertThat(response.balance()).isEqualTo(436_000L);

        verify(creditAccountRepository).findByUserId(USER_ID);
        verifyNoMoreInteractions(creditAccountRepository);
    }

    @Test
    @DisplayName("크레딧 계좌가 없으면 데이터 불일치 오류를 발생시킨다")
    void getBalance_throwsException_whenAccountDoesNotExist() {
        when(creditAccountRepository.findByUserId(USER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> creditQueryService.getBalance(USER_ID)
        )
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "사용자의 크레딧 계좌가 존재하지 않습니다."
                );

        verify(creditAccountRepository).findByUserId(USER_ID);
        verifyNoMoreInteractions(creditAccountRepository);
    }
}
