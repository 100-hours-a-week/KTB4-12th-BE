package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

public enum ProfilingResultFailure {
    TRANSIENT_DB, CONFIGURATION, UNCLASSIFIED;

    public static ProfilingResultFailure classify(Throwable failure) {
        boolean transientFailure = false;
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (Throwable cause = failure;
             cause != null && visited.add(cause);
             cause = cause.getCause()) {

            if (cause instanceof BadSqlGrammarException) {
                return CONFIGURATION;
            }

            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                int code = sql.getErrorCode();

                if (code == 1044 || code == 1045 || code == 1142
                        || state != null
                        && (state.startsWith("28") || state.startsWith("42"))) {
                    return CONFIGURATION;
                }

                transientFailure |= code == 1205 || code == 1213
                        || "40001".equals(state)
                        || state != null && state.startsWith("08")
                        || sql instanceof SQLTransientConnectionException;
            }

            transientFailure |= cause instanceof CannotAcquireLockException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotGetJdbcConnectionException;
        }

        return transientFailure ? TRANSIENT_DB : UNCLASSIFIED;
    }
}
