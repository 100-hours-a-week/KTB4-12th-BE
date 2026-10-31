package com.gift.gift.domain.auth.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.auth.entity.LoginIpRateLimit;
import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.support.LoginRateLimitProperties;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LoginRateLimitTransactionServiceTest {

    @Test
    @DisplayName("MySQL 폴백도 설정한 1초 간격으로 IP 토큰을 보충한다")
    void consumeIpToken_usesConfiguredRefillInterval() {
        String identifierHash = "a".repeat(64);
        LocalDateTime start = LocalDateTime.of(2026, 10, 30, 12, 0);
        LoginIpRateLimit rateLimit = new LoginIpRateLimit(identifierHash, start);
        for (int request = 0; request < 10; request++) {
            rateLimit.consume(start);
        }

        LoginIpRateLimitRepository ipRepository = mock(LoginIpRateLimitRepository.class);
        when(ipRepository.findByIdentifierHashForUpdate(identifierHash))
                .thenReturn(Optional.of(rateLimit));
        Clock clock = Clock.fixed(start.plusSeconds(1).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        LoginRateLimitTransactionService service = new LoginRateLimitTransactionService(
                ipRepository,
                mock(LoginEmailFailureLimitRepository.class),
                clock,
                new LoginRateLimitProperties("unused", 1)
        );

        assertTrue(service.consumeIpToken(identifierHash).permitted());
    }
}
