package com.gift.gift.domain.auth.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;

import com.gift.gift.domain.auth.service.AuthenticationDataCleanupTransactionService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuthenticationDataCleanupLockTest {

    private LockProvider lockProvider;
    private AuthenticationDataCleanupTransactionService transactionService;

    @BeforeEach
    void setUp() {
        lockProvider = mock(LockProvider.class);
        transactionService = mock(AuthenticationDataCleanupTransactionService.class);
    }

    @Test
    @DisplayName("잠금을 획득한 인스턴스만 정리를 실행하고 잠금을 반환한다")
    void cleanupAuthenticationData_runsWithLock() {
        SimpleLock lock = mock(SimpleLock.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        try (var context = createContext()) {
            context.getBean(AuthenticationDataCleanupScheduler.class).cleanupAuthenticationData();
        }

        ArgumentCaptor<LockConfiguration> configuration = ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(configuration.capture());
        assertThat(configuration.getValue().getName()).isEqualTo("cleanupAuthenticationData");
        assertThat(configuration.getValue().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(1));
        assertThat(configuration.getValue().getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(20));
        verify(transactionService).deleteSessionBatch(any(), eq(1_000));
        verify(transactionService).deleteIpRateLimitBatch(any(), eq(1_000));
        verify(transactionService).deleteEmailFailureLimitBatch(any(), any(), eq(1_000));
        verify(lock).unlock();
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 보유하면 삭제 작업을 건너뛴다")
    void cleanupAuthenticationData_skipsWhenLockUnavailable() {
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        try (var context = createContext()) {
            context.getBean(AuthenticationDataCleanupScheduler.class).cleanupAuthenticationData();
        }

        verify(lockProvider).lock(any());
        verifyNoInteractions(transactionService);
    }

    private AnnotationConfigApplicationContext createContext() {
        var context = new AnnotationConfigApplicationContext();
        context.register(LockConfig.class);
        context.registerBean(LockProvider.class, () -> lockProvider);
        context.registerBean(AuthenticationDataCleanupTransactionService.class, () -> transactionService);
        context.registerBean(Clock.class, Clock::systemUTC);
        context.registerBean(AuthenticationDataCleanupScheduler.class);
        context.refresh();
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT23H")
    static class LockConfig {
    }
}
