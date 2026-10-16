package com.gift.gift.domain.notification.support;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

public final class NotificationPersistenceFailures {
    private NotificationPersistenceFailures() {
    }

    public static boolean isRetryable(RuntimeException exception) {
        boolean hasSqlCause = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException || cause instanceof SQLRecoverableException) {
                return true;
            }
            if (cause instanceof SQLException sqlException) {
                hasSqlCause = true;
                if (sqlException.getSQLState() != null && sqlException.getSQLState().startsWith("08")) {
                    return true;
                }
            }
        }
        return exception instanceof TransientDataAccessException
                || (!hasSqlCause && exception instanceof DataAccessResourceFailureException);
    }

    public static boolean isDeduplicationConflict(DataIntegrityViolationException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if (constraintName != null) {
                    int separatorIndex = constraintName.lastIndexOf('.');
                    String unqualifiedName = separatorIndex >= 0
                            ? constraintName.substring(separatorIndex + 1)
                            : constraintName;
                    if ("uk_notifications_deduplication_key".equals(unqualifiedName)) {
                        return true;
                    }
                }
            }
            current = current.getCause();
        }
        return false;
    }
}
