package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;
import com.gift.gift.global.messaging.RabbitManagementProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProfilingResultConsumerConfigurationTest {

    @Test
    @DisplayName("기본 OFF에서는 Consumer와 Management 설정/클라이언트를 만들지 않는다")
    void disabledDoesNotRequireManagementCredentials() {
        new ApplicationContextRunner().withUserConfiguration(ProfilingResultConsumerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ProfilingResultListener.class);
                    assertThat(context).doesNotHaveBean(RabbitManagementProperties.class);
                    assertThat(context).doesNotHaveBean("rabbitManagementRestClient");
                });
    }

    @Test
    @DisplayName("결과 Consumer 활성화에는 기존 AMQP 활성화가 필수다")
    void requiresAmqpEnabled() {
        assertThatThrownBy(() -> new ProfilingResultConsumerConfiguration(new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AI_PROFILE_AMQP_ENABLED");
    }

    @Test
    @DisplayName("스케줄러 OFF와 무관하게 공유 연결·동시성1·prefetch1·수동ACK·복구0으로 구성한다")
    void configuresIndependentConservativeConsumer() throws Exception {
        var environment = new MockEnvironment().withProperty("app.ai-profile.amqp.enabled", "true")
                .withProperty("app.ai-profile.scheduling-enabled", "false");
        var configuration = new ProfilingResultConsumerConfiguration(environment);
        var connection = new CachingConnectionFactory();
        try {
            var listener = configuration.profilingResultListener(connection, mock(RabbitAdmin.class),
                    mock(RestClient.class), new RabbitManagementProperties(URI.create("http://localhost:15672"),
                            "management-reader", "test-password"), new ProfilingResultMessageReader(),
                    mock(ProfilingResultService.class), mock(ApplicationEventPublisher.class));
            var container = (SimpleMessageListenerContainer) ReflectionTestUtils.getField(listener, "container");
            assertThat(container.getConnectionFactory()).isSameAs(connection);
            assertThat(container.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.MANUAL);
            assertThat(container.getQueueNames()).containsExactly(ProfilingAmqpConfiguration.RESULT_QUEUE);
            assertThat(container.isAutoStartup()).isFalse();
            assertThat(ReflectionTestUtils.getField(container, "concurrentConsumers")).isEqualTo(1);
            assertThat(ReflectionTestUtils.getField(container, "maxConcurrentConsumers")).isEqualTo(1);
            assertThat(ReflectionTestUtils.getField(container, "prefetchCount")).isEqualTo(1);
            assertThat(ReflectionTestUtils.getField(container, "deBatchingEnabled")).isEqualTo(false);
            assertThat(container.isChannelTransacted()).isFalse();
            listener.destroy();
        } finally {
            connection.destroy();
        }
    }
}
