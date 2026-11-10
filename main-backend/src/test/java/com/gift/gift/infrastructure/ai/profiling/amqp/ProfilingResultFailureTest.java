package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.sql.SQLException;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import static org.assertj.core.api.Assertions.assertThat;
import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingResultFailure.*;

class ProfilingResultFailureTest {
    @ParameterizedTest
    @MethodSource("failures")
    @DisplayName("연결·잠금 오류만 일시 오류로 분류하고 감싸진 권한 오류와 미분류 오류는 제외한다")
    void classifiesFailures(Throwable failure, ProfilingResultFailure expected) {
        assertThat(ProfilingResultFailure.classify(failure)).isEqualTo(expected);
    }

    static Stream<Arguments> failures() {
        return Stream.of(
                Arguments.of(new SQLException("connection", "08006"), TRANSIENT_DB),
                Arguments.of(new SQLException("deadlock", "40001", 1213), TRANSIENT_DB),
                Arguments.of(new SQLException("lock timeout", "HY000", 1205), TRANSIENT_DB),
                Arguments.of(new CannotGetJdbcConnectionException("wrapped",
                        new SQLException("denied", "28000", 1045)), CONFIGURATION),
                Arguments.of(new SQLException("schema", "42S02"), CONFIGURATION),
                Arguments.of(new DataAccessResourceFailureException("unknown"), UNCLASSIFIED));
    }
}
