package com.gift.gift.domain.product.search;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.RequiredArgsConstructor;

import com.gift.gift.domain.product.search.ProductSearchOutboxRepository.Job;
import com.gift.gift.domain.product.search.ProductSearchSyncSourceRepository.Snapshot;

@RequiredArgsConstructor
public class ProductSearchSyncService {

    private final ProductSearchOutboxRepository outbox;
    private final ProductSearchSyncSourceRepository source;
    private final ProductSearchBulkWriter writer;
    private final ProductSearchSyncProperties settings;

    // ES 호출 중 DB 트랜잭션이나 행 잠금을 유지하지 않는다.
    public void syncOnce() {
        List<Job> jobs = outbox.claim(settings);

        if (jobs.isEmpty()) {
            return;
        }

        Map<Long, List<Job>> grouped = new LinkedHashMap<>();

        for (Job job : jobs) {
            List<Job> productJobs = grouped.get(job.productId());

            if (productJobs == null) {
                productJobs = new ArrayList<>();
                grouped.put(job.productId(), productJobs);
            }

            productJobs.add(job);
        }

        List<ProductSearchBulkWriter.Result> results;

        try {
            Map<Long, Snapshot> latest = source.findLatest(
                    new ArrayList<>(grouped.keySet())
            );

            List<Snapshot> snapshots = new ArrayList<>();

            for (Map.Entry<Long, List<Job>> entry : grouped.entrySet()) {
                Snapshot snapshot = latest.getOrDefault(
                        entry.getKey(),
                        Snapshot.physicallyDeleted(entry.getKey())
                );

                long claimedVersion = entry.getValue().stream()
                        .mapToLong(Job::productVersion)
                        .max()
                        .orElseThrow();

                if (snapshot.version() < claimedVersion) {
                    throw new IllegalStateException(
                            "상품 원본 버전이 Outbox보다 과거입니다."
                    );
                }

                snapshots.add(snapshot);
            }

            results = writer.write(snapshots);
        } catch (Exception exception) {
            boolean retryable =
                    !(exception instanceof ElasticsearchException elastic)
                            || ProductSearchBulkWriter.isRetryable(
                            elastic.status()
                    );

            outbox.fail(
                    jobs,
                    retryable,
                    exception.getClass().getSimpleName(),
                    settings
            );

            return;
        }

        for (ProductSearchBulkWriter.Result result : results) {
            List<Job> productJobs = grouped.get(result.productId());

            if (result.success()) {
                outbox.complete(productJobs);
            } else {
                outbox.fail(
                        productJobs,
                        result.retryable(),
                        result.errorType(),
                        settings
                );
            }
        }
    }
}
