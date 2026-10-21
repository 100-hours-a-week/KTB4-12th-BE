package com.gift.gift.domain.order.service;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;

import com.gift.gift.domain.order.exception.OrderErrorCode;
import com.gift.gift.domain.order.exception.OrderException;

import static org.assertj.core.api.Assertions.assertThat;

class LocalPaymentFailureClassifierTest {

    @Test
    @DisplayName("SQLState 40001과 MySQL 1213이 모두 일치할 때만 교착 상태로 분류한다")
    void classify_returnsDeadlockOnlyWhenSqlStateAndErrorCodeMatch() {
        assertThat(classifySql("40001", 1213))
                .isEqualTo(LocalPaymentFailureType.DEADLOCK);
        assertThat(classifySql("40001", 1205))
                .isEqualTo(LocalPaymentFailureType.UNKNOWN);
        assertThat(classifySql("HY000", 1213))
                .isEqualTo(LocalPaymentFailureType.UNKNOWN);
    }

    @Test
    @DisplayName("DB 연결 장애는 일시적인 인프라 오류로 분류한다")
    void classify_returnsTransientInfrastructureForDatabaseConnectionFailure() {
        RuntimeException exception = new DataAccessResourceFailureException("DB 연결 실패");

        assertThat(LocalPaymentFailureClassifier.classify(exception))
                .isEqualTo(LocalPaymentFailureType.TRANSIENT_INFRASTRUCTURE);
    }

    @Test
    @DisplayName("재고와 크레딧 부족은 영구 업무 오류로 분류한다")
    void classify_returnsPermanentBusinessForBusinessFailure() {
        assertThat(LocalPaymentFailureClassifier.classify(
                new OrderException(OrderErrorCode.INSUFFICIENT_STOCK)
        )).isEqualTo(LocalPaymentFailureType.PERMANENT_BUSINESS);

        assertThat(LocalPaymentFailureClassifier.classify(
                new OrderException(OrderErrorCode.INSUFFICIENT_CREDIT)
        )).isEqualTo(LocalPaymentFailureType.PERMANENT_BUSINESS);
    }

    @Test
    @DisplayName("무결성과 명확한 프로그래밍 오류를 별도로 분류한다")
    void classify_returnsDataIntegrityOrProgramming() {
        assertThat(LocalPaymentFailureClassifier.classify(
                new DataIntegrityViolationException("원장 UNIQUE 위반")
        )).isEqualTo(LocalPaymentFailureType.DATA_INTEGRITY_OR_PROGRAMMING);

        assertThat(LocalPaymentFailureClassifier.classify(
                new NullPointerException("예상하지 못한 null")
        )).isEqualTo(LocalPaymentFailureType.DATA_INTEGRITY_OR_PROGRAMMING);
    }

    @Test
    @DisplayName("알 수 없는 런타임 예외는 미분류 오류로 유지한다")
    void classify_returnsUnknownForUnclassifiedFailure() {
        assertThat(LocalPaymentFailureClassifier.classify(
                new RuntimeException("분류되지 않은 오류")
        )).isEqualTo(LocalPaymentFailureType.UNKNOWN);
    }

    private LocalPaymentFailureType classifySql(String sqlState, int errorCode) {
        return LocalPaymentFailureClassifier.classify(
                new RuntimeException(
                        new SQLException("DB 오류", sqlState, errorCode)
                )
        );
    }
}
