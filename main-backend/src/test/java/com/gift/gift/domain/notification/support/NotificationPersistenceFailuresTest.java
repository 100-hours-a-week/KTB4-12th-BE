package com.gift.gift.domain.notification.support;

import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationPersistenceFailuresTest {
    @Test
    @DisplayName("커밋 예외로 감싸진 일시적 연결 실패도 재처리한다")
    void commitConnectionFailureIsRetryable() {
        assertThat(NotificationPersistenceFailures.isRetryable(new TransactionSystemException("commit failed",
                new SQLTransientConnectionException("connection interrupted")))).isTrue();
        assertThat(NotificationPersistenceFailures.isRetryable(new TransactionSystemException("commit failed",
                new SQLNonTransientConnectionException("connection lost", "08006")))).isTrue();
    }

    @Test
    @DisplayName("인증 설정 오류는 커밋 예외에 감싸져도 자동 재시도하지 않는다")
    void invalidDatabaseCredentialsAreNotRetryable() {
        var invalidCredentials = new SQLInvalidAuthorizationSpecException("access denied", "28000");
        assertThat(NotificationPersistenceFailures.isRetryable(new TransactionSystemException("cannot connect",
                invalidCredentials))).isFalse();
        assertThat(NotificationPersistenceFailures.isRetryable(
                new DataAccessResourceFailureException("cannot connect", invalidCredentials)))
                .isFalse();
    }
}
