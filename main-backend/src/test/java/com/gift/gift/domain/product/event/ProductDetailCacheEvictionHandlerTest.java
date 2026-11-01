package com.gift.gift.domain.product.event;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

import com.gift.gift.domain.product.cache.ProductDetailCacheRepository;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class ProductDetailCacheEvictionHandlerTest {

    private ProductDetailCacheRepository cacheRepository;
    private AnnotationConfigApplicationContext context;

    @BeforeEach
    void setUp() {
        cacheRepository = mock(ProductDetailCacheRepository.class);

        context = new AnnotationConfigApplicationContext();

        context.registerBean(
                TransactionalEventListenerFactory.class,
                TransactionalEventListenerFactory::new
        );
        context.registerBean(
                ProductDetailCacheRepository.class,
                () -> cacheRepository
        );
        context.registerBean(ProductDetailCacheEvictionHandler.class);

        context.refresh();
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }

        TransactionSynchronizationManager.clear();
        context.close();
    }

    @Test
    @DisplayName("상품 메타데이터 변경 트랜잭션이 커밋된 후에만 캐시를 삭제한다")
    void deletesCacheOnlyAfterCommit() {
        beginTransaction();

        context.publishEvent(new ProductMetadataChangedEvent(399L));

        verifyNoInteractions(cacheRepository);

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(cacheRepository).deleteById(399L);
    }

    @Test
    @DisplayName("상품 메타데이터 변경 트랜잭션이 롤백되면 캐시를 삭제하지 않는다")
    void rollbackDoesNotDeleteCache() {
        beginTransaction();

        context.publishEvent(new ProductMetadataChangedEvent(399L));

        completeTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);

        verifyNoInteractions(cacheRepository);
    }

    @Test
    @DisplayName("트랜잭션 없이 이벤트가 발행되면 캐시를 삭제하지 않는다")
    void eventWithoutTransactionDoesNotDeleteCache() {
        context.publishEvent(new ProductMetadataChangedEvent(399L));

        verifyNoInteractions(cacheRepository);
    }

    @Test
    @DisplayName("캐시 삭제가 일시적으로 실패하면 재시도하여 삭제한다")
    void retriesDeletionAfterTemporaryFailure() {
        doThrow(new DataAccessResourceFailureException("temporary failure"))
                .doNothing()
                .when(cacheRepository).deleteById(399L);

        beginTransaction();
        context.publishEvent(new ProductMetadataChangedEvent(399L));

        completeTransaction(TransactionSynchronization.STATUS_COMMITTED);

        verify(cacheRepository, times(2)).deleteById(399L);
    }

    @Test
    @DisplayName("캐시 삭제가 세 번 실패하면 예외를 전파하지 않고 재시도를 종료한다")
    void stopsAfterThreeFailures() {
        doThrow(new DataAccessResourceFailureException("Redis unavailable"))
                .when(cacheRepository).deleteById(399L);

        beginTransaction();
        context.publishEvent(new ProductMetadataChangedEvent(399L));

        assertThatCode(() ->
                completeTransaction(TransactionSynchronization.STATUS_COMMITTED)
        ).doesNotThrowAnyException();

        verify(cacheRepository, times(3)).deleteById(399L);
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    private void completeTransaction(int status) {
        try {
            TransactionSynchronizationUtils.triggerAfterCompletion(status);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }
}
