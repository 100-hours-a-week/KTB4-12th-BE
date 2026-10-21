package com.gift.gift.domain.credit.dto.response;

import java.util.Objects;

import com.gift.gift.domain.credit.entity.CreditAccount;

public record CreditBalanceResponse(long balance) {

    public static CreditBalanceResponse from(CreditAccount account) {

        Objects.requireNonNull(account, "크레딧 계좌는 null일 수 없습니다.");
        Objects.requireNonNull(account.getBalance(), "크레딧 잔액은 null일 수 없습니다.");

        return new CreditBalanceResponse(account.getBalance());
    }
}
