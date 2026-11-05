package com.gift.gift.global.outbox;

import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.outbox.OutboxEventWriter.Event;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "app.scheduling.enabled=false")
class OutboxIntegrationTest {
    @Autowired private OutboxEventWriter writer;
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
    @DisplayName("트랜잭션 없는 기록을 거부하고 업무와 Outbox를 함께 롤백한다")
    void requiresTransactionAndRollsBackWithBusiness() {
        assertThatThrownBy(() -> writer.enqueue(event("outside")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            writer.enqueue(event("rollback"));
            throw new IllegalStateException("업무 실패");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=?", Long.class, type)).isZero();
    }

    @Test
    @DisplayName("업무 중복 키는 DB가 차단하고 대소문자가 다른 키는 구분한다")
    void enforcesCaseSensitiveDeduplication() {
        enqueue("A");
        assertThatThrownBy(() -> enqueue("A")).isInstanceOf(DataIntegrityViolationException.class);
        enqueue("a");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE event_type=? AND status='PENDING'", Long.class, type)).isEqualTo(2L);
    }

    @Test
    @DisplayName("공통 Writer와 테이블은 이벤트 유형별 버전 필수 규칙을 적용하지 않는다")
    void acceptsMissingVersionRegardlessOfEventType() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            status.setRollbackOnly();
            Event event = new Event("product.search.sync.v1", 1, UUID.randomUUID().toString(),
                    "PRODUCT", "42", null, mapper.readTree("{\"value\":1}"), LocalDateTime.now(), null);
            String eventId = writer.enqueue(event);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_event WHERE event_id=? AND aggregate_version IS NULL",
                    Long.class, eventId)).isEqualTo(1L);
        });
    }
}
