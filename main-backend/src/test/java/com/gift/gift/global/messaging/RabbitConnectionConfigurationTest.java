package com.gift.gift.global.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitConnectionConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class,
                    RabbitHealthContributorAutoConfiguration.class))
            .withUserConfiguration(RabbitConnectionConfiguration.class, RabbitPublisherConfiguration.class,
                    ProfilingAmqpConfiguration.class)
            .withPropertyValues(
                    "spring.rabbitmq.host=shared-host.invalid", "spring.rabbitmq.port=5678",
                    "spring.rabbitmq.username=shared-user", "spring.rabbitmq.password=test-password",
                    "spring.rabbitmq.virtual-host=shared-vhost", "spring.rabbitmq.dynamic=false",
                    "spring.rabbitmq.connection-timeout=3s", "spring.rabbitmq.requested-heartbeat=20s",
                    "spring.rabbitmq.publisher-confirm-type=correlated", "spring.rabbitmq.publisher-returns=true",
                    "management.health.rabbit.enabled=false",
                    "app.outbox.rabbit.exchange=reports.test",
                    "app.outbox.rabbit.search-routing-key=search.changed",
                    "app.ai-profile.amqp.host=profiling-host.invalid",
                    "app.ai-profile.amqp.port=5679", "app.ai-profile.amqp.virtual-host=profiling");

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    @DisplayName("기능 활성화 조합과 무관하게 모든 발행 경로는 명시한 공통 접속 설정을 사용한다")
    void featureCombinations_useSharedConnection(boolean outbox, boolean profiling) {
        runner.withPropertyValues("app.outbox.enabled=" + outbox,
                "app.ai-profile.amqp.enabled=" + profiling).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(CachingConnectionFactory.class);
            assertThat(context).doesNotHaveBean("profilingConnectionFactory");
            CachingConnectionFactory factory = context.getBean("rabbitConnectionFactory", CachingConnectionFactory.class);
            assertThat(factory.getHost()).isEqualTo("shared-host.invalid");
            assertThat(factory.getPort()).isEqualTo(5678);
            assertThat(factory.getVirtualHost()).isEqualTo("shared-vhost");
            assertThat(factory.getUsername()).isEqualTo("shared-user");
            assertThat(context.containsBean("outboxRabbitTemplate")).isEqualTo(outbox);
            assertThat(context.containsBean("profilingRabbitTemplate")).isEqualTo(profiling);
            assertThat(context.containsBean("profilingRabbitAdmin")).isEqualTo(profiling);
            assertThat(context.containsBean("rabbitHealthIndicator")).isEqualTo(outbox || profiling);
            assertThat(context).doesNotHaveBean("rabbitHealthContributor");
            if (outbox) {
                assertThat(context.getBean("outboxRabbitTemplate", RabbitTemplate.class).getConnectionFactory())
                        .isSameAs(factory);
            }
            if (profiling) {
                assertThat(context.getBean("profilingRabbitTemplate", RabbitTemplate.class).getConnectionFactory())
                        .isSameAs(factory);
            }
            // 존재하지 않는 호스트로도 설정을 로드하므로 기동 시 외부 접속이 없음을 검증한다.
            if (outbox || profiling) {
                assertThat(factory.isPublisherConfirms()).isTrue();
                assertThat(factory.isPublisherReturns()).isTrue();
                assertThat(factory.getChannelCacheSize()).isEqualTo(25);
                assertThat(factory.getCloseTimeout()).isEqualTo(3000);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"exchange", "search-routing-key"})
    @DisplayName("두 기능을 함께 켤 때 공통 Outbox 라우팅 설정이 비어 있으면 기동 시 거부한다")
    void bothEnabled_requireOutboxRouting(String missingProperty) {
        runner.withPropertyValues("app.outbox.enabled=true", "app.ai-profile.amqp.enabled=true",
                        "app.outbox.rabbit." + missingProperty + "=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("app.outbox.rabbit");
                });
    }

    @Test
    @DisplayName("MQ 기능 활성화 시 공통 인증 정보가 비어 있으면 기동 시 거부한다")
    void missingSharedCredentials_failsAtStartup() {
        runner.withPropertyValues("app.ai-profile.amqp.enabled=true", "spring.rabbitmq.password=")
                .run(context -> assertThat(context).hasFailed());
    }
}
