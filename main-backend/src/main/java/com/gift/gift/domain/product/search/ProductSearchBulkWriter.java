package com.gift.gift.domain.product.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.json.JsonData;

import com.gift.gift.domain.product.search.ProductSearchSyncSourceRepository.Snapshot;

public class ProductSearchBulkWriter {

    private static final String SCRIPT = """
            if (ctx._source.search_version == null ||
                params.document.search_version > ctx._source.search_version) {
                ctx._source.clear();
                ctx._source.putAll(params.document);
            } else {
                ctx.op = 'noop';
            }
            """;

    private final ElasticsearchClient client;
    private final String indexName;

    public ProductSearchBulkWriter(
            ElasticsearchClient client,
            String indexName
    ) {
        this.client = client;
        this.indexName = indexName;
    }

    public List<Result> write(
            List<Snapshot> snapshots
    ) throws IOException {
        if (snapshots.isEmpty()) {
            return List.of();
        }

        BulkRequest.Builder request = new BulkRequest.Builder();

        for (Snapshot snapshot : snapshots) {
            request.operations(operation ->
                    operation.update(update -> update
                            .index(indexName)
                            .id(Long.toString(snapshot.productId()))
                            .retryOnConflict(3)
                            .action(action -> action
                                    .script(script -> script
                                            .lang("painless")
                                            .source(source ->
                                                    source.scriptString(
                                                            SCRIPT
                                                    )
                                            )
                                            .params(
                                                    "document",
                                                    JsonData.of(
                                                            snapshot.source()
                                                    )
                                            )
                                    )
                                    .scriptedUpsert(true)
                                    .upsert(Map.of())
                            )
                    )
            );
        }

        BulkResponse response = client.bulk(request.build());

        if (response.items().size() != snapshots.size()) {
            throw new IOException(
                    "ES Bulk 응답 항목 수가 일치하지 않습니다."
            );
        }

        List<Result> results = new ArrayList<>();

        for (int index = 0; index < snapshots.size(); index++) {
            Snapshot snapshot = snapshots.get(index);
            BulkResponseItem item = response.items().get(index);

            if (!Long.toString(snapshot.productId()).equals(item.id())) {
                throw new IOException(
                        "ES Bulk 응답 상품 ID가 일치하지 않습니다."
                );
            }

            boolean success =
                    item.error() == null
                            && item.status() >= 200
                            && item.status() < 300;

            String errorType = item.error() == null
                    ? "BulkStatus" + item.status()
                    : item.error().type();

            results.add(new Result(
                    snapshot.productId(),
                    success,
                    isRetryable(item.status()),
                    errorType.substring(
                            0,
                            Math.min(255, errorType.length())
                    )
            ));
        }

        return results;
    }

    public static boolean isRetryable(int status) {
        return status == 408
                || status == 409
                || status == 429
                || status >= 500;
    }

    public record Result(
            long productId,
            boolean success,
            boolean retryable,
            String errorType
    ) {
    }
}
