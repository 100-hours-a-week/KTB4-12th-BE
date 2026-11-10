package com.gift.gift.domain.bugreport.service;

import java.sql.SQLException;
import java.time.Clock;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;
import com.gift.gift.domain.bugreport.dto.CreateBugReportRequest;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.repository.BugReportReceiptRepository;
import com.gift.gift.domain.bugreport.support.BugReportRateLimiter;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.global.outbox.OutboxEventWriter;
import com.gift.gift.global.exception.ErrorCode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BugReportServiceTest {
    private PlatformTransactionManager transactions;
    private OutboxEventWriter writer;
    private BugReportReceiptRepository receipts;
    private BugReportService service;
    private CreateBugReportRequest request;
    private UserRepository users;
    private BugReportRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        writer = mock(OutboxEventWriter.class);
        receipts = mock(BugReportReceiptRepository.class);
        users = mock(UserRepository.class);
        rateLimiter = mock(BugReportRateLimiter.class);
        service = new BugReportService(rateLimiter, users,
            writer, receipts, transactions, JsonMapper.builder().build(), Clock.systemUTC());
        request = new CreateBugReportRequest(UUID.randomUUID().toString(), "버그", null, null,
            null, null, null, null);
    }

    @Test
    @DisplayName("인증 사용자의 이메일을 서버에서 조회하고 원본 필드와 시각을 Outbox에 저장한다")
    void snapshotsAuthenticatedReporter() {
        User user = mock(User.class);
        when(user.getEmail()).thenReturn("reporter@example.invalid");
        when(users.findByIdAndStatusAndDeletedAtIsNull(7L, UserStatus.ACTIVE)).thenReturn(Optional.of(user));
        Instant reportedAt = Instant.parse("2026-11-07T06:00:00Z");
        request = new CreateBugReportRequest(request.reportId(), "원문", "제안", "https://example.invalid",
            "/page", "800x600", "browser", reportedAt);
        service.submitBugReport(7L, "test", request);
        var event = ArgumentCaptor.forClass(OutboxEventWriter.Event.class);
        verify(writer).enqueue(event.capture());
        var payload = event.getValue().payload();
        assertThat(payload.path("userId").asLong()).isEqualTo(7L);
        assertThat(payload.path("email").asString()).isEqualTo("reporter@example.invalid");
        assertThat(payload.path("description").asString()).isEqualTo("원문");
        assertThat(payload.path("route").asString()).isEqualTo("/page");
        assertThat(payload.path("reportedAt").asString()).isEqualTo(reportedAt.toString());
        verify(rateLimiter).checkUser(7L);
        verify(rateLimiter).checkIp("test");
    }

    @Test
    @DisplayName("익명 접수는 사용자 조회와 사용자별 제한을 수행하지 않는다")
    void acceptsAnonymousWithoutUserLookup() {
        service.submitBugReport(null, "test", request);
        verifyNoInteractions(users);
        verify(rateLimiter, never()).checkUser(any());
        verify(rateLimiter).checkIp("test");
    }

    @Test
    @DisplayName("INSERT 성공 후 commit이 실패해도 접수 성공으로 응답하지 않는다")
    void failsOnCommitFailure() {
        doThrow(new TransactionSystemException("commit failed")).when(transactions).commit(any());
        assertThatThrownBy(() -> service.submitBugReport(null, "test", request))
            .isInstanceOfSatisfying(BugReportException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(writer).enqueue(any());
        verifyNoInteractions(receipts);
    }

    @Test
    @DisplayName("다른 UNIQUE 충돌은 동일 리포트 접수로 오인하지 않는다")
    void rejectsUnrelatedUniqueConflict() {
        when(writer.enqueue(any())).thenThrow(new DuplicateKeyException("duplicate",
            new SQLException("Duplicate entry for key uk_outbox_event_id", "23000", 1062)));
        when(receipts.exists(any())).thenReturn(true);
        assertThatThrownBy(() -> service.submitBugReport(null, "test", request))
            .isInstanceOfSatisfying(BugReportException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));
        verify(transactions).rollback(any());
        verifyNoInteractions(receipts);
    }
}
