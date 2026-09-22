package com.gift.gift.domain.user.repository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.auth.service.LoginRateLimitTransactionService;
import com.gift.gift.domain.auth.entity.LoginEmailFailureLimit;
import com.gift.gift.domain.auth.entity.LoginIpRateLimit;
import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.support.LoginRateLimitDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        properties = {
                "spring.jpa.hibernate.ddl-auto=update",
                "app.security.login-rate-limit.hmac-secret-base64="
                        + "MDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDA="
        }
)
class LoginRateLimitRepositoryTest {

    private static final LocalDateTime TEST_TIME =
            LocalDateTime.of(2026, 9, 20, 12, 0);

    @Autowired
    private LoginIpRateLimitRepository ipRepository;

    @Autowired
    private LoginEmailFailureLimitRepository emailRepository;

    @Autowired
    private LoginRateLimitTransactionService transactionService;

    @Autowired
    private Clock clock;

    @AfterEach
    void cleanUp() {
        emailRepository.deleteAll();
        ipRepository.deleteAll();
    }

    @Test
    @Transactional
    @DisplayName("IP 요청 제한 행을 생성하고 조회할 수 있다")
    void insertIfAbsent_persistsIpRateLimit() {
        String identifierHash = uniqueHash("ip");

        ipRepository.insertIfAbsent(
                identifierHash,
                LocalDateTime.now(clock)
        );

        assertTrue(ipRepository.existsById(identifierHash));

        assertEquals(
                0,
                ipRepository.findById(identifierHash)
                        .orElseThrow()
                        .getAvailableTokens()
                        .compareTo(
                                java.math.BigDecimal.valueOf(10)
                        )
        );
    }

    @Test
    @Transactional
    @DisplayName("동일한 IP 식별자 생성 요청을 반복해도 행은 하나만 유지한다")
    void insertIfAbsent_doesNotCreateDuplicateIpRows() {
        String identifierHash = uniqueHash("ip");

        ipRepository.insertIfAbsent(
                identifierHash,
                LocalDateTime.now(clock)
        );

        ipRepository.insertIfAbsent(
                identifierHash,
                LocalDateTime.now(clock)
        );

        assertEquals(
                1,
                ipRepository.findAll()
                        .stream()
                        .filter(rateLimit ->
                                rateLimit
                                        .getIdentifierHash()
                                        .equals(identifierHash)
                        )
                        .count()
        );
    }

    @Test
    @DisplayName("동일 IP의 동시 20회 요청 중 정확히 10회만 통과한다")
    void consumeIpToken_allowsExactlyTenConcurrentRequests()
            throws Exception {
        String identifierHash = uniqueHash("ip");
        int requestCount = 20;

        List<LoginRateLimitDecision> decisions =
                executeConcurrently(
                        requestCount,
                        () -> transactionService
                                .consumeIpToken(identifierHash)
                );

        long permittedCount = decisions.stream()
                .filter(LoginRateLimitDecision::permitted)
                .count();

        long rejectedCount = decisions.stream()
                .filter(decision -> !decision.permitted())
                .count();

        assertEquals(10, permittedCount);
        assertEquals(10, rejectedCount);

        assertEquals(
                0,
                ipRepository.findById(identifierHash)
                        .orElseThrow()
                        .getAvailableTokens()
                        .compareTo(java.math.BigDecimal.ZERO)
        );
    }

    @Test
    @DisplayName("동일 이메일의 동시 실패 6회에서 다섯 번은 기록되고 한 번은 제한된다")
    void recordEmailFailure_serializesConcurrentUpdates()
            throws Exception {
        String identifierHash = uniqueHash("email");
        int requestCount = 6;

        List<LoginRateLimitDecision> decisions =
                executeConcurrently(
                        requestCount,
                        () -> transactionService
                                .recordEmailFailure(
                                        identifierHash
                                )
                );

        long permittedCount = decisions.stream()
                .filter(LoginRateLimitDecision::permitted)
                .count();

        long rejectedCount = decisions.stream()
                .filter(decision -> !decision.permitted())
                .count();

        assertEquals(5, permittedCount);
        assertEquals(1, rejectedCount);

        var saved = emailRepository
                .findById(identifierHash)
                .orElseThrow();

        assertEquals(1, saved.getBackoffLevel());
        assertEquals(0, saved.getFailureCount());
    }

    @Test
    @DisplayName("로그인 성공 초기화는 이메일 실패 상태 행을 삭제한다")
    void resetEmailFailure_deletesFailureState() {
        String identifierHash = uniqueHash("email");

        transactionService.recordEmailFailure(
                identifierHash
        );

        assertTrue(
                emailRepository.existsById(identifierHash)
        );

        transactionService.resetEmailFailure(
                identifierHash
        );

        assertTrue(
                emailRepository
                        .findById(identifierHash)
                        .isEmpty()
        );
    }

    private <T> List<T> executeConcurrently(
            int taskCount,
            ThrowingSupplier<T> operation
    ) throws Exception {
        ExecutorService executor =
                Executors.newFixedThreadPool(taskCount);

        CountDownLatch ready =
                new CountDownLatch(taskCount);

        CountDownLatch start =
                new CountDownLatch(1);

        try {
            List<Future<T>> futures =
                    new ArrayList<>();

            for (int task = 0; task < taskCount; task++) {
                futures.add(
                        executor.submit(() -> {
                            ready.countDown();
                            start.await();

                            return operation.get();
                        })
                );
            }

            ready.await();
            start.countDown();

            List<T> results =
                    new ArrayList<>();

            for (Future<T> future : futures) {
                results.add(future.get());
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private String uniqueHash(String prefix) {
        String source = prefix
                + UUID.randomUUID()
                .toString()
                .replace("-", "");

        return String.format(
                "%-64s",
                source
        ).replace(' ', '0');
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {

        T get() throws Exception;
    }
}
