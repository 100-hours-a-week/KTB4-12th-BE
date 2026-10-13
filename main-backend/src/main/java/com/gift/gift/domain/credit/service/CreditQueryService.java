package com.gift.gift.domain.credit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.dto.response.CreditBalanceResponse;
import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CreditQueryService {

    private final CreditAccountRepository creditAccountRepository;

    public CreditBalanceResponse getBalance(Long userId) {
        CreditAccount account = creditAccountRepository
                .findByUserId(userId)
                .orElseThrow(() -> new IllegalStateException(
                        "사용자의 크레딧 계좌가 존재하지 않습니다."
                ));

        return CreditBalanceResponse.from(account);
    }
}
