package com.gift.gift.global.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxClaimRepository.Job;
import com.gift.gift.global.outbox.OutboxEventWriter.Event;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"app.outbox.max-failures=2", "app.scheduling.enabled=false"})
class OutboxClaimIntegrationTest {
    @Autowired private OutboxEventWriter writer;
    @Autowired private OutboxClaimRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private PlatformTransactionManager transactionManager;
    private final String type = "test." + UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM outbox_event WHERE event_type = ?", type);
    }

    private Event event(String key) {
        return new Event(type, 1, key, "TEST", "42", null,
                mapper.readTree("{\"value\":1}"), LocalDateTime.now(), null);
    }

    private String enqueue(String key) {
        return new TransactionTemplate(transactionManager).execute(status -> writer.enqueue(event(key)));
    }

    @Test
    @DisplayName("임대 만료 복구는 eventId를 유지하고 이전 워커의 늦은 결과를 거부한다")
    void recoversLeaseAndFencesStaleResults() {
        String eventId = enqueue("lease");
        Job old = repository.claim(type, 1).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", old.id());
        assertThat(repository.complete(old)).isFalse();
        repository.fail(old, "LateFailure");
        assertThat(repository.claim(type, 1)).isEmpty();
        assertThat(state(old)).isEqualTo("PENDING");
        due(old);
        Job current = repository.claim(type, 1).getFirst();
        assertThat(current.claimToken()).isNotEqualTo(old.claimToken());
        assertThat(current.envelope().eventId()).isEqualTo(eventId);
        assertThat(repository.complete(old)).isFalse();
        repository.fail(old, "LateFailure");
        assertThat(repository.complete(current)).isTrue();
        assertThat(state(current)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("발행 실패는 백오프 후 재시도하고 한도 초과 작업은 보존한다")
    void retriesAndPreservesExhaustedJobs() {
        enqueue("retry");
        Job first = repository.claim(type, 1).getFirst();
        repository.fail(first, "ConfirmTimeout");
        assertThat(repository.claim(type, 1)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT TIMESTAMPDIFF(SECOND,updated_at,next_attempt_at) FROM outbox_event WHERE id=?
                """, Integer.class, first.id())).isEqualTo(2);
        due(first);
        Job second = repository.claim(type, 1).getFirst();
        repository.fail(second, "BrokerNack");
        assertThat(state(second)).isEqualTo("FAILED");
        assertThat(repository.claim(type, 1)).isEmpty();
    }

    @Test
    @DisplayName("동시 Relay는 SKIP LOCKED로 서로 다른 작업을 선점한다")
    void concurrentClaimsDoNotOverlap() throws Exception {
        enqueue("one");
        enqueue("two");
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<List<Job>> task = () -> repository.claim(type, 1);
            var results = executor.invokeAll(List.of(task, task));
            assertThat(results.getFirst().get()).hasSize(1);
            assertThat(results.getLast().get()).hasSize(1);
            assertThat(results.getFirst().get().getFirst().id())
                    .isNotEqualTo(results.getLast().get().getFirst().id());
        }
    }

    @Test
    @DisplayName("완료 커밋은 호출자 롤백과 독립적이며 다른 유형은 선점하지 않는다")
    void commitsCompletionIndependently() {
        enqueue("complete");
        assertThat(repository.claim("unknown", 1)).isEmpty();
        Job job = repository.claim(type, 1).getFirst();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            repository.complete(job);
            throw new IllegalStateException("호출자 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(job)).isEqualTo("PUBLISHED");
    }

    private void due(Job job) {
        jdbc.update("UPDATE outbox_event SET next_attempt_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", job.id());
    }

    @Test
    @DisplayName("다른 트랜잭션이 잠근 행은 기다리지 않고 다음 작업을 선점한다")
    void skipsLockedRows() {
        String lockedId = enqueue("locked");
        String availableId = enqueue("available");
        try (var executor = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.queryForObject("SELECT id FROM outbox_event WHERE event_id=? FOR UPDATE", Long.class, lockedId);
                var claim = executor.submit(() -> repository.claim(type, 1));
                try {
                    assertThat(claim.get(10, TimeUnit.SECONDS)).extracting(job -> job.envelope().eventId())
                            .containsExactly(availableId);
                } catch (Exception exception) {
                    throw new IllegalStateException("잠긴 행을 건너뛰지 못했습니다.", exception);
                }
            });
        }
    }

    @Test
    @DisplayName("임대 만료도 실패 한도를 소모하고 완료 상태 제약은 DB가 보호한다")
    void exhaustedLeasesAndInvalidStateAreRejected() {
        enqueue("expired");
        Job first = repository.claim(type, 1).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", first.id());
        repository.claim(type, 1);
        due(first);
        Job second = repository.claim(type, 1).getFirst();
        jdbc.update("UPDATE outbox_event SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", second.id());
        assertThat(repository.claim(type, 1)).isEmpty();
        assertThat(state(second)).isEqualTo("FAILED");
        assertThatThrownBy(() -> jdbc.update("UPDATE outbox_event SET status='PUBLISHED' WHERE id=?", second.id()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("chk_outbox_event_published");
    }

    private String state(Job job) {
        return jdbc.queryForObject("SELECT status FROM outbox_event WHERE id=?", String.class, job.id());
    }
}
