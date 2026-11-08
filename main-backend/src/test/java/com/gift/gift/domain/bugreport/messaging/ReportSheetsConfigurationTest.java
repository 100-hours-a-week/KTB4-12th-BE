package com.gift.gift.domain.bugreport.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;
import com.gift.gift.infrastructure.google.GoogleBugReportClient;
import com.gift.gift.infrastructure.google.GoogleServiceAccountTokenProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportSheetsConfigurationTest {
    private ApplicationContextRunner runner() {
        var tokens = mock(GoogleServiceAccountTokenProvider.class);
        when(tokens.accountScope()).thenReturn("project:account");
        when(tokens.projectScope()).thenReturn("project");
        return new ApplicationContextRunner().withUserConfiguration(ReportConsumerConfiguration.class)
            .withPropertyValues("app.report.sheets.enabled=true", "app.outbox.enabled=true",
                "app.rabbitmq.management.url=http://127.0.0.1:1",
                "app.rabbitmq.management.username=test", "app.rabbitmq.management.password=test")
            .withBean("rabbitConnectionFactory", CachingConnectionFactory.class, CachingConnectionFactory::new)
            .withBean("stringRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(GoogleBugReportClient.class, () -> mock(GoogleBugReportClient.class))
            .withBean(GoogleServiceAccountTokenProvider.class, () -> tokens)
            .withBean(RabbitPublisher.class, () -> mock(RabbitPublisher.class))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new);
    }

    @Test
    @DisplayName("Sheets만 활성화하면 Discord 웹훅 없이 별도 리스너가 기동하고 브로커 부재 시 대기한다")
    void sheetsStartsIndependently() {
        runner().withBean(BugReportGoogleSheetsProperties.class,
            () -> new BugReportGoogleSheetsProperties("configured", "sheet", "tab"))
            .run(context -> assertThat(context).hasNotFailed().hasBean("reportSheetsListener")
                .doesNotHaveBean("reportDiscordListener"));
    }

    @Test
    @DisplayName("두 채널은 별도 리스너를 가지며 누락된 Sheets 설정은 활성화 시 거부한다")
    void validatesSettingsAndCreatesBothListeners() {
        runner().withBean(BugReportGoogleSheetsProperties.class,
            () -> new BugReportGoogleSheetsProperties("", "", ""))
            .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("app.report.discord.enabled=true")
            .withBean(BugReportGoogleSheetsProperties.class,
                () -> new BugReportGoogleSheetsProperties("configured", "sheet", "tab"))
            .withBean(BugReportWebhookProperties.class, () -> new BugReportWebhookProperties("https://test/webhook"))
            .withBean(DiscordWebhookClient.class, () -> mock(DiscordWebhookClient.class))
            .run(context -> {
                assertThat(context).hasNotFailed().hasBean("reportSheetsListener").hasBean("reportDiscordListener");
                assertThat(context.getBean("reportSheetsListener")).isNotSameAs(context.getBean("reportDiscordListener"));
            });
    }
}
