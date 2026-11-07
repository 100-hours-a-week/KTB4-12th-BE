package com.gift.gift.domain.bugreport.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gift.gift.global.outbox.OutboxRelay;
import com.gift.gift.global.messaging.RabbitManagementProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ReportMqConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ReportMqConfiguration.class);

    @Test
    @DisplayName("기본 비활성 환경에서는 브로커나 Management 설정 없이 리포트 MQ 빈을 만들지 않는다")
    void disabledRequiresNoBroker() {
        runner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(ReportOutboxRelay.class).doesNotHaveBean(ReportTopologyPreflight.class)
                .doesNotHaveBean(RabbitManagementProperties.class).doesNotHaveBean("rabbitManagementRestClient"));
    }

    @Test
    @DisplayName("공통 Management 설정과 리포트 배치 설정을 독립적으로 바인딩한다")
    void bindsSharedManagementAndReportBatchSeparately() {
        configured().withPropertyValues("app.outbox.enabled=true", "app.report.mq.batch-size=7")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("rabbitManagementRestClient");
                    assertThat(context.getBean(RabbitManagementProperties.class).url().toString())
                            .isEqualTo("http://invalid:1");
                    assertThat(context.getBean(ReportMqProperties.class).batchSize()).isEqualTo(7);
                });
    }

    @Test
    @DisplayName("공통 Management 인증 누락이나 허용하지 않는 URL은 활성화 시 거부한다")
    void rejectsMissingCredentialsAndInvalidManagementUrl() {
        for (String property : new String[]{"app.rabbitmq.management.username=",
                "app.rabbitmq.management.password=", "app.rabbitmq.management.url=ftp://invalid",
                "app.rabbitmq.management.url=http://user:secret@invalid"}) {
            configured().withPropertyValues("app.outbox.enabled=true", property)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test
    @DisplayName("리포트 MQ 활성화는 공통 Outbox 활성화와 관리 접속 설정을 요구한다")
    void enabledRequiresOutboxAndCredentials() {
        runner.withPropertyValues("app.report.mq.enabled=true")
                .run(context -> assertThat(context).hasFailed());
        configured().withPropertyValues("app.outbox.enabled=false")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("리포트 토폴로지는 앱이 선언하지 않고 스케줄러가 발행 전에 검사한다")
    void enabledDoesNotContactOrDeclareBrokerAtStartup() {
        configured().withPropertyValues("app.outbox.enabled=true").run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(ReportOutboxRelay.class)
                        .doesNotHaveBean(org.springframework.amqp.rabbit.core.RabbitAdmin.class));
    }

    private ApplicationContextRunner configured() {
        return runner.withBean("rabbitConnectionFactory", CachingConnectionFactory.class,
                        CachingConnectionFactory::new)
                .withBean(OutboxRelay.class, () -> mock(OutboxRelay.class))
                .withPropertyValues("app.report.mq.enabled=true", "app.rabbitmq.management.url=http://invalid:1",
                        "app.rabbitmq.management.username=test", "app.rabbitmq.management.password=test");
    }
}
