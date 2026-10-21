package com.gift.gift.domain.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductSearchSyncConfigTest {
    @Test
    @DisplayName("기본 비활성 상태에서는 ES 동기화 작업자 빈을 생성하지 않는다")
    void doesNotCreateWorkerByDefault() {
        new ApplicationContextRunner().withUserConfiguration(ProductSearchSyncConfig.class)
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ProductSearchSyncService.class)
                        .doesNotHaveBean(ProductSearchSyncScheduler.class));
    }

    @Test
    @DisplayName("검색 활성화와 별개로 동기화 서비스를 생성하고 전역 스케줄링 설정을 따른다")
    void separatesSearchAndSyncFlags() {
        new ApplicationContextRunner().withUserConfiguration(ProductSearchSyncConfig.class)
                .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
                .withBean(ProductSearchOutboxRepository.class, () -> mock(ProductSearchOutboxRepository.class))
                .withBean(ProductSearchSyncSourceRepository.class, () -> mock(ProductSearchSyncSourceRepository.class))
                .withPropertyValues("app.product-search.sync.enabled=true", "app.product-search.enabled=false",
                        "app.scheduling.enabled=false", "app.product-search.index-name=test-index")
                .run(context -> assertThat(context)
                        .hasSingleBean(ProductSearchSyncService.class)
                        .doesNotHaveBean(ProductSearchSyncScheduler.class));
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "app.product-search.sync.batch-size=0",
            "app.product-search.sync.lease-seconds=0",
            "app.product-search.sync.max-failures=0",
            "app.product-search.sync.retry-max-seconds=1",
            "app.product-search.sync.poll-interval-ms=0"
    })
    @DisplayName("잘못된 동기화 설정은 작업자를 시작하기 전에 차단한다")
    void rejectsInvalidWorkerSettings(String invalidProperty) {
        new ApplicationContextRunner().withUserConfiguration(ProductSearchSyncConfig.class)
                .withBean(ElasticsearchClient.class, () -> mock(ElasticsearchClient.class))
                .withBean(ProductSearchOutboxRepository.class, () -> mock(ProductSearchOutboxRepository.class))
                .withBean(ProductSearchSyncSourceRepository.class, () -> mock(ProductSearchSyncSourceRepository.class))
                .withPropertyValues("app.product-search.sync.enabled=true",
                        "app.scheduling.enabled=false", "app.product-search.index-name=test-index", invalidProperty)
                .run(context -> assertThat(context).hasFailed());
    }

}
