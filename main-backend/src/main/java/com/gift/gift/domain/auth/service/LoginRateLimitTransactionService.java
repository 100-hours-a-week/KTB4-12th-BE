package com.gift.gift.domain.auth.service;

import java.time.Clock;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.auth.entity.LoginEmailFailureLimit;
import com.gift.gift.domain.auth.entity.LoginIpRateLimit;
import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

@Service
@RequiredArgsConstructor
public class LoginRateLimitTransactionService {

    private final LoginIpRateLimitRepository ipRepository;
    private final LoginEmailFailureLimitRepository emailRepository;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginRateLimitDecision consumeIpToken(
            String identifierHash
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        ipRepository.insertIfAbsent(
                identifierHash,
                now
        );

        LoginIpRateLimit rateLimit = ipRepository
                .findByIdentifierHashForUpdate(identifierHash)
                .orElseThrow(() -> new IllegalStateException(
                        "IP 요청 제한 데이터가 생성되지 않았습니다."
                ));

        return rateLimit.consume(now);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            readOnly = false
    )
    public LoginRateLimitDecision inspectEmail(
            String identifierHash
    ) {
        return emailRepository
                .findByIdentifierHashForUpdate(identifierHash)
                .map(failureLimit -> failureLimit.inspect(
                        LocalDateTime.now(clock)
                ))
                .orElseGet(LoginRateLimitDecision::permit);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LoginRateLimitDecision recordEmailFailure(
            String identifierHash
    ) {
        LocalDateTime now = LocalDateTime.now(clock);

        emailRepository.insertIfAbsent(
                identifierHash,
                now
        );

        LoginEmailFailureLimit failureLimit =
                emailRepository
                        .findByIdentifierHashForUpdate(
                                identifierHash
                        )
                        .orElseThrow(() ->
                                new IllegalStateException(
                                        "이메일 실패 제한 데이터가 생성되지 않았습니다."
                                )
                        );

        return failureLimit.recordFailure(now);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resetEmailFailure(
            String identifierHash
    ) {
        emailRepository.deleteById(identifierHash);
    }
}
