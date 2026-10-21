package com.gift.gift.domain.product.search;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.core.bulk.OperationType;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.gift.gift.domain.product.search.ProductSearchSyncSourceRepository.Snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProductSearchBulkWriterTest {
    @Test
    @DisplayName("HTTP 200인 Bulk에서도 각 항목의 성공·재시도·영구 실패를 구분한다")
    void checksEveryItemAndUsesVersionScript() throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        List<BulkResponseItem> items = List.of(
                item("1", 200, null), item("2", 429, "busy"), item("3", 400, "mapping_error"));
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b ->
                b.errors(true).took(1).items(items)));
        ProductSearchBulkWriter writer = new ProductSearchBulkWriter(client, "test-index");

        List<ProductSearchBulkWriter.Result> results = writer.write(List.of(
                new Snapshot(1, 1, Map.of("search_version", 1L)),
                new Snapshot(2, 1, Map.of("search_version", 1L)),
                new Snapshot(3, 1, Map.of("search_version", 1L))));

        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(1).retryable()).isTrue();
        assertThat(results.get(2).success()).isFalse();
        assertThat(results.get(2).retryable()).isFalse();
        ArgumentCaptor<BulkRequest> request = ArgumentCaptor.forClass(BulkRequest.class);
        verify(client).bulk(request.capture());
        assertThat(request.getValue().operations().getFirst().update().action().scriptedUpsert()).isTrue();
        assertThat(request.getValue().operations().getFirst().update().action()
                .script().source().scriptString()).contains("ctx.op = 'noop'");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Bulk 응답의 개수나 상품 ID가 다르면 완료 결과로 사용하지 않는다")
    void rejectsMismatchedBulkResponse(boolean wrongId) throws Exception {
        ElasticsearchClient client = mock(ElasticsearchClient.class);
        List<BulkResponseItem> items = wrongId ? List.of(item("999", 200, null)) : List.of();
        when(client.bulk(any(BulkRequest.class))).thenReturn(BulkResponse.of(b ->
                b.errors(false).took(1).items(items)));
        ProductSearchBulkWriter writer = new ProductSearchBulkWriter(client, "test-index");

        assertThatThrownBy(() -> writer.write(List.of(
                new Snapshot(1, 1, Map.of("search_version", 1L)))))
                .isInstanceOf(IOException.class);
    }

    private BulkResponseItem item(String id, int status, String type) {
        return BulkResponseItem.of(b -> {
            b.operationType(OperationType.Update).index("test-index").id(id).status(status);
            if (type != null) {
                b.error(e -> e.type(type).reason("test"));
            }
            return b;
        });
    }
}
