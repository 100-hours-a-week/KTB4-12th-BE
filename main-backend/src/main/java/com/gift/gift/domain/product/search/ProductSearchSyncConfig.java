package com.gift.gift.domain.product.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.product-search.sync",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(ProductSearchSyncProperties.class)
public class ProductSearchSyncConfig {

    @Bean
    public ProductSearchBulkWriter productSearchBulkWriter(
            ElasticsearchClient client,
            @Value("${app.product-search.index-name}") String indexName
    ) {
        return new ProductSearchBulkWriter(client, indexName);
    }

    @Bean
    public ProductSearchSyncService productSearchSyncService(
            ProductSearchOutboxRepository outbox,
            ProductSearchSyncSourceRepository source,
            ProductSearchBulkWriter writer,
            ProductSearchSyncProperties properties
    ) {
        return new ProductSearchSyncService(
                outbox,
                source,
                writer,
                properties
        );
    }

    @Bean
    @ConditionalOnProperty(
            name = "app.scheduling.enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public ProductSearchSyncScheduler productSearchSyncScheduler(
            ProductSearchSyncService service,
            ProductSearchSyncProperties properties
    ) {
        return new ProductSearchSyncScheduler(service, properties);
    }
}
