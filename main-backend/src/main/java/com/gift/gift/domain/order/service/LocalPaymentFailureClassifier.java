package com.gift.gift.domain.order.service;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;

import com.gift.gift.domain.order.exception.OrderException;
import com.gift.gift.global.exception.ErrorCode;

public final class LocalPaymentFailureClassifier {

    private static final String DEADLOCK_SQL_STATE = "40001";
    private static final int DEADLOCK_ERROR_CODE = 1213;

    private LocalPaymentFailureClassifier() {
    }

    public static LocalPaymentFailureType classify(RuntimeException exception) {
        if (isDeadlock(exception)) {
            return LocalPaymentFailureType.DEADLOCK;
        }

        if (isPermanentBusinessFailure(exception)) {
            return LocalPaymentFailureType.PERMANENT_BUSINESS;
        }

        if (isDataIntegrityOrProgrammingFailure(exception)) {
            return LocalPaymentFailureType.DATA_INTEGRITY_OR_PROGRAMMING;
        }

        if (isTransientInfrastructureFailure(exception)) {
            return LocalPaymentFailureType.TRANSIENT_INFRASTRUCTURE;
        }

        return LocalPaymentFailureType.UNKNOWN;
    }

    private static boolean isDeadlock(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException
                    && DEADLOCK_SQL_STATE.equals(sqlException.getSQLState())
                    && sqlException.getErrorCode() == DEADLOCK_ERROR_CODE) {
                return true;
            }
        }

        return false;
    }

    private static boolean isPermanentBusinessFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof OrderException orderException) {
                ErrorCode errorCode = orderException.getErrorCode();

                return errorCode == ErrorCode.INSUFFICIENT_STOCK
                        || errorCode == ErrorCode.INSUFFICIENT_CREDIT
                        || errorCode == ErrorCode.PRODUCT_NOT_FOUND;
            }
        }

        return false;
    }

    private static boolean isDataIntegrityOrProgrammingFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataIntegrityViolationException
                    || cause instanceof NullPointerException
                    || cause instanceof IllegalArgumentException
                    || cause instanceof IllegalStateException) {
                return true;
            }
        }

        return false;
    }

    private static boolean isTransientInfrastructureFailure(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientException
                    || cause instanceof SQLRecoverableException
                    || cause instanceof TransientDataAccessException
                    || cause instanceof DataAccessResourceFailureException) {
                return true;
            }

            if (cause instanceof SQLException sqlException
                    && sqlException.getSQLState() != null
                    && sqlException.getSQLState().startsWith("08")) {
                return true;
            }
        }

        return false;
    }
}
