package com.gift.gift.domain.order.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.mysql.MySQLContainer;

import com.gift.gift.domain.order.service.PaymentReconciliationService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentReconciliationSchedulerTest {

    @Test
    @DisplayName("설정된 실행 주기를 최소 잠금으로 사용하고 정상 종료 시 잠금을 반환한다")
    void reconcilePendingPayments_usesConfiguredLockAndUnlocks() {
        LockProvider lockProvider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        PaymentReconciliationService service = mock(PaymentReconciliationService.class);

        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        try (AnnotationConfigApplicationContext context =
                     createContext(lockProvider, service, "PT2M")) {
            context.getBean(PaymentReconciliationScheduler.class)
                    .reconcilePendingPayments();
        }

        ArgumentCaptor<LockConfiguration> configuration =
                ArgumentCaptor.forClass(LockConfiguration.class);

        verify(lockProvider).lock(configuration.capture());
        assertThat(configuration.getValue().getName())
                .isEqualTo("reconcilePendingPayments");
        assertThat(configuration.getValue().getLockAtLeastFor())
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(configuration.getValue().getLockAtMostFor())
                .isEqualTo(Duration.ofHours(1));

        verify(service).reconcilePendingPayments();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 보유하면 재조정을 건너뛴다")
    void reconcilePendingPayments_skipsWhenLockUnavailable() {
        LockProvider lockProvider = mock(LockProvider.class);
        PaymentReconciliationService service = mock(PaymentReconciliationService.class);

        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        try (AnnotationConfigApplicationContext context =
                     createContext(lockProvider, service, "PT1M")) {
            context.getBean(PaymentReconciliationScheduler.class)
                    .reconcilePendingPayments();
        }

        verify(lockProvider).lock(any());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("공유 DB에서 잠금은 중첩 실행을 막고 최소 잠금 시간이 지나면 다시 획득된다")
    void sharedDatabaseLock_preventsOverlapAndAllowsNextRun() throws Exception {
        try (MySQLContainer mysql = new MySQLContainer("mysql:8.4")) {
            mysql.start();

            DataSource firstDataSource = dataSource(mysql);
            DataSource secondDataSource = dataSource(mysql);

            new JdbcTemplate(firstDataSource).execute("""
                    CREATE TABLE shedlock (
                        name VARCHAR(64) NOT NULL,
                        lock_until DATETIME(3) NOT NULL,
                        locked_at DATETIME(3) NOT NULL,
                        locked_by VARCHAR(255) NOT NULL,
                        CONSTRAINT pk_shedlock PRIMARY KEY (name)
                    )
                    """);

            LockProvider first = jdbcLockProvider(firstDataSource);
            LockProvider second = jdbcLockProvider(secondDataSource);

            LockConfiguration configuration = new LockConfiguration(
                    Instant.now(),
                    "reconcilePendingPayments",
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(1)
            );

            Optional<SimpleLock> firstLock = first.lock(configuration);

            assertThat(firstLock).isPresent();
            assertThat(second.lock(configuration)).isEmpty();

            firstLock.orElseThrow().unlock();

            assertThat(second.lock(configuration)).isEmpty();

            Thread.sleep(1_200);

            Optional<SimpleLock> nextLock = second.lock(new LockConfiguration(
                    Instant.now(),
                    "reconcilePendingPayments",
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(1)
            ));

            assertThat(nextLock).isPresent();
            nextLock.orElseThrow().unlock();
        }
    }

    private AnnotationConfigApplicationContext createContext(
            LockProvider lockProvider,
            PaymentReconciliationService service,
            String interval
    ) {
        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource(
                        "paymentReconciliationTest",
                        Map.of("app.payment.reconciliation.interval", interval)
                )
        );
        context.register(LockConfig.class);
        context.registerBean(LockProvider.class, () -> lockProvider);
        context.registerBean(PaymentReconciliationService.class, () -> service);
        context.registerBean(PaymentReconciliationScheduler.class);
        context.refresh();

        return context;
    }

    private DataSource dataSource(MySQLContainer mysql) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(mysql.getJdbcUrl());
        dataSource.setUsername(mysql.getUsername());
        dataSource.setPassword(mysql.getPassword());
        return dataSource;
    }

    private LockProvider jdbcLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }

    @Configuration(proxyBeanMethods = false)
    @EnableSchedulerLock(defaultLockAtMostFor = "PT23H")
    static class LockConfig {
    }
}
