package com.gift.gift.domain.bugreport.messaging;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportDiscordConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ReportDiscordConfiguration.class);

    @Test
    @DisplayName("기본 OFF에서는 MQ·Redis·웹훅 설정 없이 기동한다")
    void disabledDoesNotRequireInfrastructure() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ReportDiscordListener.class));
    }

    @Test
    @DisplayName("Consumer만 켜도 공통 접속을 사용하고 브로커 부재 시 소비를 보류한다")
    void consumerEnabledIndependentlyDefersUnavailableBroker() {
        runner.withPropertyValues("app.report.discord.enabled=true", "app.report.mq.enabled=false",
                "app.outbox.enabled=true",
                "app.rabbitmq.management.url=http://127.0.0.1:1",
                "app.rabbitmq.management.username=test", "app.rabbitmq.management.password=test")
            .withBean("rabbitConnectionFactory", CachingConnectionFactory.class, CachingConnectionFactory::new)
            .withBean(BugReportWebhookProperties.class, () -> new BugReportWebhookProperties("http://localhost/webhook"))
            .withBean("stringRedisTemplate", StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(DiscordWebhookClient.class, () -> mock(DiscordWebhookClient.class))
            .withBean(RabbitPublisher.class, () -> mock(RabbitPublisher.class))
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(ReportDiscordListener.class)
                .doesNotHaveBean(ReportOutboxRelay.class));
    }
}
