package com.gift.gift.domain.product.scheduler;

import java.time.Duration;
import java.util.Optional;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.product.service.ProductViewSyncService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductViewSyncSchedulerTest {

    @Test
    @DisplayName("반영 주기를 최소, 주기의 2배를 최대 잠금 시간으로 쓰고 종료 시 잠금을 반환한다")
    void sync_locksWithInterval() {
        LockProvider lockProvider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        ProductViewSyncService service = mock(ProductViewSyncService.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));

        new ProductViewSyncScheduler(service, lockProvider, Duration.ofMinutes(10)).sync();

        ArgumentCaptor<LockConfiguration> configuration =
                ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockProvider).lock(configuration.capture());
        assertThat(configuration.getValue().getName()).isEqualTo("syncProductViews");
        assertThat(configuration.getValue().getLockAtLeastFor()).isEqualTo(Duration.ofMinutes(10));
        assertThat(configuration.getValue().getLockAtMostFor()).isEqualTo(Duration.ofMinutes(20));
        verify(service).sync();
        verify(lock).unlock();
    }

    @Test
    @DisplayName("다른 인스턴스가 잠금을 보유하면 반영을 건너뛴다")
    void sync_skipsWhenLocked() {
        LockProvider lockProvider = mock(LockProvider.class);
        ProductViewSyncService service = mock(ProductViewSyncService.class);
        when(lockProvider.lock(any())).thenReturn(Optional.empty());

        new ProductViewSyncScheduler(service, lockProvider, Duration.ofMinutes(10)).sync();

        verify(lockProvider).lock(any());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("반영이 실패해도 예외를 밖으로 던지지 않고 잠금을 반환한다")
    void sync_swallowsFailure() {
        LockProvider lockProvider = mock(LockProvider.class);
        SimpleLock lock = mock(SimpleLock.class);
        ProductViewSyncService service = mock(ProductViewSyncService.class);
        when(lockProvider.lock(any())).thenReturn(Optional.of(lock));
        when(service.sync()).thenThrow(new IllegalStateException("의도한 반영 실패"));

        new ProductViewSyncScheduler(service, lockProvider, Duration.ofMinutes(10)).sync();

        verify(service).sync();
        verify(lock).unlock();
    }
}
