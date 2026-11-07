package com.gift.gift.domain.bugreport.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.util.AopTestUtils;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.dto.CreateBugReportRequest;
import com.gift.gift.global.outbox.OutboxEventWriter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "app.scheduling.enabled=false")
@AutoConfigureMockMvc
class BugReportAcceptanceIntegrationTest {
    @Autowired private BugReportService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @MockitoSpyBean private OutboxEventWriter writer;
    private final List<String> reportIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        reportIds.forEach(id -> jdbc.update(
            "DELETE FROM outbox_event WHERE event_type='report.created.v1' AND deduplication_key=?", id));
    }

    @Test
    @DisplayName("202 응답 시 이미 커밋된 PENDING 이벤트가 있고 클라이언트 사용자 정보는 저장하지 않는다")
    void commitsBeforeReturning202() throws Exception {
        String id = newId();
        mvc.perform(post("/bug-report").contentType("application/json")
                .content("{\"reportId\":\"" + id + "\",\"description\":\"원문\","
                    + "\"userId\":123,\"email\":\"spoof@example.invalid\"}"))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.reportId").value(id));
        var row = jdbc.queryForMap("SELECT * FROM outbox_event WHERE deduplication_key=?", id);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("event_type")).isEqualTo("report.created.v1");
        var payload = mapper.readTree(row.get("payload").toString());
        assertThat(payload.path("description").asString()).isEqualTo("원문");
        assertThat(payload.path("userId").isNull()).isTrue();
        assertThat(payload.path("email").asString()).isEmpty();
        assertThat(payload.path("category").asString()).isEqualTo("버그");
        assertThat(payload.path("reportedAt").asString()).isNotEmpty();
        assertThat(payload.has("embeds")).isFalse();
    }

    @Test
    @DisplayName("동일 ID 재접수는 최초 payload와 eventId 및 FAILED 상태를 유지한다")
    void preservesFirstReceipt() {
        String id = newId();
        service.submitBugReport(null, id, request(id.toUpperCase(), "최초"));
        var first = jdbc.queryForMap("SELECT event_id,payload FROM outbox_event WHERE deduplication_key=?", id);
        jdbc.update("UPDATE outbox_event SET status='FAILED' WHERE deduplication_key=?", id);
        assertThat(service.submitBugReport(null, id, request(id, "수정"))).isEqualTo(id);
        var after = jdbc.queryForMap("SELECT event_id,payload FROM outbox_event WHERE deduplication_key=?", id);
        assertThat(after).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_event WHERE deduplication_key=?",
            String.class, id)).isEqualTo("FAILED");
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("동시에 같은 ID를 접수해도 하나의 이벤트만 남고 모든 요청이 동일 ID를 받는다")
    void deduplicatesConcurrentRequests() throws Exception {
        String id = newId();
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                String text = "내용" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    return service.submitBugReport(null, id, request(id, text));
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).isEqualTo(id);
            }
        }
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("실제 INSERT 뒤 오류가 나면 롤백하고 503을 반환하며 같은 ID로 다시 접수할 수 있다")
    void rollsBackFailedAcceptance() throws Exception {
        String id = newId();
        OutboxEventWriter target = AopTestUtils.getUltimateTargetObject(writer);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new DataIntegrityViolationException("failure after insert");
        }).when(target).enqueue(any());
        String body = mapper.writeValueAsString(request(id, "재접수"));
        mvc.perform(post("/bug-report").contentType("application/json").content(body))
            .andExpect(status().isServiceUnavailable());
        assertThat(count(id)).isZero();
        doCallRealMethod().when(target).enqueue(any());
        mvc.perform(post("/bug-report").contentType("application/json").content(body))
            .andExpect(status().isAccepted());
        assertThat(count(id)).isEqualTo(1);
    }

    private String newId() {
        String id = UUID.randomUUID().toString();
        reportIds.add(id);
        return id;
    }

    private int count(String id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE deduplication_key=?", Integer.class, id);
    }

    private CreateBugReportRequest request(String id, String description) {
        return new CreateBugReportRequest(id, description, null, null, null, null, null, null);
    }
}
