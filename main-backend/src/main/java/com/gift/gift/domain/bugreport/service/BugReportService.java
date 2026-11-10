package com.gift.gift.domain.bugreport.service;

import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import com.gift.gift.domain.bugreport.dto.CreateBugReportRequest;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.repository.BugReportReceiptRepository;
import com.gift.gift.domain.bugreport.support.BugReportRateLimiter;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.entity.UserStatus;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.outbox.OutboxEventWriter;

@Service
@RequiredArgsConstructor
public class BugReportService {
    private final BugReportRateLimiter rateLimiter;
    private final UserRepository userRepository;
    private final OutboxEventWriter writer;
    private final BugReportReceiptRepository receipts;
    private final PlatformTransactionManager transactionManager;
    private final ObjectMapper mapper;
    private final Clock clock;

    public String submitBugReport(Long userId, String clientIp, CreateBugReportRequest request) {
        if (userId != null) {
            rateLimiter.checkUser(userId);
        }
        rateLimiter.checkIp(clientIp);
        String reportId = UUID.fromString(request.reportId()).toString();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                ObjectNode payload = mapper.valueToTree(request);
                payload.put("reportId", reportId);
                payload.put("category", request.category() == null || request.category().isBlank()
                        ? "버그" : request.category());
                for (String field : new String[]{"pageUrl", "route", "viewport", "userAgent"}) {
                    if (payload.path(field).isNull()) {
                        payload.put(field, "");
                    }
                }
                payload.put("reportedAt", (request.reportedAt() == null
                        ? clock.instant() : request.reportedAt()).toString());
                if (userId == null) {
                    payload.putNull("userId");
                } else {
                    payload.put("userId", userId);
                }
                payload.put("email", userId == null ? "" : userRepository
                        .findByIdAndStatusAndDeletedAtIsNull(userId, UserStatus.ACTIVE)
                        .map(User::getEmail).orElse(""));
                writer.enqueue(new OutboxEventWriter.Event("report.created.v1", 1, reportId,
                        "REPORT", reportId, null, payload, LocalDateTime.now(clock), MDC.get("traceId")));
            });
        } catch (DuplicateKeyException exception) {
            try {
                Throwable cause = exception.getMostSpecificCause();
                if (!(cause instanceof SQLException sql) || sql.getErrorCode() != 1062
                        || !sql.getMessage().contains("uk_outbox_event_dedup") || !receipts.exists(reportId)) {
                    throw new BugReportException(BugReportErrorCode.ACCEPTANCE_FAILED);
                }
            } catch (DataAccessException failure) {
                throw new BugReportException(BugReportErrorCode.ACCEPTANCE_FAILED);
            }
        } catch (DataAccessException | TransactionException exception) {
            throw new BugReportException(BugReportErrorCode.ACCEPTANCE_FAILED);
        }
        return reportId;
    }
}
