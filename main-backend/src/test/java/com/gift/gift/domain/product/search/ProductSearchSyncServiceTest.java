package com.gift.gift.domain.product.search;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import com.gift.gift.domain.product.search.ProductSearchOutboxRepository.Job;
import com.gift.gift.domain.product.search.ProductSearchSyncSourceRepository.Snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

class ProductSearchSyncServiceTest {
    private final ProductSearchOutboxRepository outbox = mock(ProductSearchOutboxRepository.class);
    private final ProductSearchSyncSourceRepository source = mock(ProductSearchSyncSourceRepository.class);
    private final ProductSearchBulkWriter writer = mock(ProductSearchBulkWriter.class);
    private final ProductSearchSyncProperties settings = new ProductSearchSyncProperties(100, 60, 3, 2, 30, 5000);
    private final ProductSearchSyncService service = new ProductSearchSyncService(outbox, source, writer, settings);

    @Test
    @DisplayName("상품별 최신 상태를 한 번만 보내고 Bulk 항목별로 완료와 재시도를 구분한다")
    void mergesJobsAndHandlesPartialFailure() throws Exception {
        Job first = new Job(1, 10, 1, "token");
        Job second = new Job(2, 10, 2, "token");
        Job third = new Job(3, 20, 1, "token");
        Snapshot latest = new Snapshot(10, 3, Map.of("id", 10L, "search_version", 3L));
        when(outbox.claim(settings)).thenReturn(List.of(first, second, third));
        when(source.findLatest(List.of(10L, 20L))).thenReturn(Map.of(10L, latest));
        when(writer.write(anyList())).thenReturn(List.of(
                new ProductSearchBulkWriter.Result(10, true, false, ""),
                new ProductSearchBulkWriter.Result(20, false, true, "es_rejected_execution_exception")));

        service.syncOnce();

        ArgumentCaptor<List<Snapshot>> sent = ArgumentCaptor.captor();
        verify(writer).write(sent.capture());
        assertThat(sent.getValue()).hasSize(2);
        assertThat(sent.getValue().getFirst()).isEqualTo(latest);
        assertThat(sent.getValue().getLast().version()).isEqualTo(Long.MAX_VALUE);
        verify(outbox).complete(List.of(first, second));
        verify(outbox).fail(List.of(third), true, "es_rejected_execution_exception", settings);
    }

    @Test
    @DisplayName("ES 연결 실패 시 완료 처리하지 않고 확보한 작업을 재시도한다")
    void retriesAfterConnectionFailure() throws Exception {
        Job job = new Job(1, 10, 1, "token");
        when(outbox.claim(settings)).thenReturn(List.of(job));
        when(source.findLatest(anyList())).thenReturn(Map.of());
        when(writer.write(anyList())).thenThrow(new IOException("connection closed"));

        service.syncOnce();

        verify(outbox).fail(List.of(job), true, "IOException", settings);
        verify(outbox, never()).complete(anyList());
    }

    @Test
    @DisplayName("원본 버전이 작업 버전보다 낮으면 ES에 보내거나 완료하지 않는다")
    void refusesOlderSource() throws Exception {
        Job job = new Job(1, 10, 2, "token");
        when(outbox.claim(settings)).thenReturn(List.of(job));
        when(source.findLatest(anyList())).thenReturn(Map.of(10L,
                new Snapshot(10, 1, Map.of("id", 10L))));

        service.syncOnce();

        verifyNoInteractions(writer);
        verify(outbox).fail(List.of(job), true, "IllegalStateException", settings);
    }
    @Test
    @DisplayName("확보할 작업이 없으면 상품 조회와 ES 호출을 하지 않는다")
    void skipsEmptyBatch() {
        when(outbox.claim(settings)).thenReturn(List.of());

        service.syncOnce();

        verifyNoInteractions(source, writer);
    }

    @Test
    @DisplayName("원본 DB 조회가 실패하면 선점한 작업을 완료하지 않고 재시도한다")
    void retriesSourceReadFailure() {
        Job job = new Job(1, 10, 1, "token");
        when(outbox.claim(settings)).thenReturn(List.of(job));
        when(source.findLatest(anyList())).thenThrow(new DataAccessResourceFailureException("read failed"));

        service.syncOnce();

        verifyNoInteractions(writer);
        verify(outbox).fail(List.of(job), true, "DataAccessResourceFailureException", settings);
        verify(outbox, never()).complete(anyList());
    }

    @Test
    @DisplayName("ES 반영 후 완료 기록이 실패하면 성공한 ES 반영을 되돌리거나 영구 실패 처리하지 않는다")
    void leavesAckFailureForLeaseRecovery() throws Exception {
        Job job = new Job(1, 10, 1, "token");
        when(outbox.claim(settings)).thenReturn(List.of(job));
        when(source.findLatest(anyList())).thenReturn(Map.of());
        when(writer.write(anyList())).thenReturn(List.of(
                new ProductSearchBulkWriter.Result(10, true, false, "")));
        doThrow(new DataAccessResourceFailureException("ack failed")).when(outbox).complete(List.of(job));

        assertThatThrownBy(service::syncOnce)
                .isInstanceOf(DataAccessResourceFailureException.class);

        verify(writer).write(anyList());
        verify(outbox, never()).fail(anyList(), anyBoolean(), anyString(), any());
    }

}
