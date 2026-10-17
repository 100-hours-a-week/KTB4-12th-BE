package com.gift.gift.domain.notification.service;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.notification.repository.NotificationOutboxRepository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class NotificationOutboxSettingsTest {

    @ParameterizedTest
    @ValueSource(ints = {20, 100})
    @DisplayName("설정한 배치 크기를 SQL LIMIT에 바인딩한다")
    void repositoryBindsConfiguredBatchSize(int batchSize) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        try (var context = context(Map.of("app.notification.outbox.batch-size", batchSize), jdbc)) {
            context.register(NotificationOutboxRepository.class);
            context.refresh();
            context.getBean(NotificationOutboxRepository.class).findDueIds();
            verify(jdbc).queryForList(contains("LIMIT ?"), eq(Long.class), eq(batchSize));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("0 이하 배치 크기는 시작 시 거부한다")
    void repositoryRejectsNonPositiveBatchSize(int batchSize) {
        try (var context = context(Map.of("app.notification.outbox.batch-size", batchSize), mock(JdbcTemplate.class))) {
            context.register(NotificationOutboxRepository.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("0 이하 폴링 지연은 스케줄러 시작 전에 거부한다")
    void schedulerRejectsNonPositiveDelay(int delay) {
        try (var context = context(Map.of("app.notification.outbox.poll-delay-ms", delay), mock(JdbcTemplate.class))) {
            context.registerBean(NotificationOutboxDispatcher.class, () -> mock(NotificationOutboxDispatcher.class));
            context.register(NotificationOutboxScheduler.class);
            assertThatThrownBy(context::refresh).hasRootCauseInstanceOf(IllegalArgumentException.class);
        }
    }

    private AnnotationConfigApplicationContext context(Map<String, Object> properties, JdbcTemplate jdbc) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("outbox-settings", properties));
        context.registerBean(JdbcTemplate.class, () -> jdbc);
        return context;
    }
}
