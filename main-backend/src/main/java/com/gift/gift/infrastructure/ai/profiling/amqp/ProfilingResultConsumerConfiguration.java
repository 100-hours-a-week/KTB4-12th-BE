package com.gift.gift.infrastructure.ai.profiling.amqp;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;
import com.gift.gift.global.messaging.RabbitManagementConfiguration;
import com.gift.gift.global.messaging.RabbitManagementProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.ai-profile.result-consumer",
        name = "enabled",
        havingValue = "true"
)
@Import(RabbitManagementConfiguration.class)
public class ProfilingResultConsumerConfiguration {

    public ProfilingResultConsumerConfiguration(
            Environment environment
    ) {
        Assert.isTrue(
                environment.getProperty(
                        "app.ai-profile.amqp.enabled",
                        Boolean.class,
                        false
                ),
                "결과 Consumer는 AI_PROFILE_AMQP_ENABLED=true가 필요합니다."
        );
    }

    @Bean
    public ProfilingResultListener profilingResultListener(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory,

            @Qualifier("profilingRabbitAdmin")
            RabbitAdmin admin,

            @Qualifier("rabbitManagementRestClient")
            RestClient managementClient,

            RabbitManagementProperties management,
            ProfilingResultMessageReader reader,
            ProfilingResultService service,
            ApplicationEventPublisher events
    ) {
        Assert.state(
                !connectionFactory.getRabbitConnectionFactory()
                        .isAutomaticRecoveryEnabled(),
                "Rabbit Java Client의 별도 자동 복구를 사용하지 않습니다."
        );

        var preflight = new ProfilingResultTopologyPreflight(
                managementClient,
                management.url(),
                connectionFactory.getVirtualHost(),
                connectionFactory.getRabbitConnectionFactory()
                        .getUsername()
        );

        var container = new SimpleMessageListenerContainer(
                connectionFactory
        );

        container.setQueueNames(
                ProfilingAmqpConfiguration.RESULT_QUEUE
        );

        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);
        container.setMaxConcurrentConsumers(1);
        container.setPrefetchCount(1);
        container.setBatchSize(1);
        container.setConsumerBatchEnabled(false);
        container.setDeBatchingEnabled(false);

        container.setChannelTransacted(false);
        container.setExposeListenerChannel(true);

        container.setAutoStartup(false);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);
        container.setDeclarationRetries(0);

        // 미분류 예외가 기본 설정으로 DLQ에 격리되지 않도록 한다.
        container.setDefaultRequeueRejected(true);

        container.setShutdownTimeout(30000);
        container.setForceCloseChannel(true);
        container.setForceStop(true);

        // PR3에서는 연결 장애 후 자동 소비 재시작을 하지 않는다.
        container.setRecoveryBackOff(new FixedBackOff(0, 0));

        container.setApplicationEventPublisher(events);

        var lifecycle = new ProfilingResultListener(
                container,
                admin,
                preflight
        );

        container.setMessageListener(
                new ProfilingResultMessageListener(
                        reader,
                        service,
                        lifecycle::halt,
                        lifecycle::isHalted
                )
        );

        container.setErrorHandler(
                exception -> lifecycle.halt("CONTAINER_ERROR")
        );

        return lifecycle;
    }
}
