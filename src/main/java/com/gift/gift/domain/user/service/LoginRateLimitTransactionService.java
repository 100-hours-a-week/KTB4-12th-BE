package com.gift.gift.domain.user.service;

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
                        "IP rate limit row was not created"
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
                                        "Email failure limit row was not created"
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
