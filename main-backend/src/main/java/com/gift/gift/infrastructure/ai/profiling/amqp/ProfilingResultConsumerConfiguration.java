package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.lang.management.ManagementFactory;
import java.time.Clock;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
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
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final Environment environment;

    public ProfilingResultConsumerConfiguration(
            Environment environment
    ) {
        this.environment = environment;

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
            ApplicationEventPublisher events,
            DataSource dataSource,
            MeterRegistry meters,
            Clock clock
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
                connectionFactory.getRabbitConnectionFactory().getUsername()
        );

        var jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(3);

        Runnable probe = () -> {
            // 읽기 점검이므로 실제 저장의 재시도·재중지 경로도 유지한다.
            jdbc.queryForList(
                    "SELECT request_id FROM profiling_jobs WHERE 1 = 0"
            );
            preflight.verify();
        };

        var container =
                new SimpleMessageListenerContainer(connectionFactory);

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
        container.setConsumerStartTimeout(5000);

        container.setDefaultRequeueRejected(true);

        container.setShutdownTimeout(30000);
        container.setForceCloseChannel(true);
        container.setForceStop(true);

        // 재시작은 아래 상태 관리자가 소유한다.
        container.setRecoveryBackOff(new FixedBackOff(0, 0));
        container.setApplicationEventPublisher(events);

        var executor =
                Executors.newSingleThreadScheduledExecutor(task -> {
                    var thread = new Thread(
                            task,
                            "profiling-result-recovery"
                    );
                    thread.setDaemon(true);
                    return thread;
                });

        int resumeLimit = environment.getProperty(
                "app.ai-profile.result-consumer.automatic-resume-limit",
                Integer.class,
                3
        );

        int unknownLimit = environment.getProperty(
                "app.ai-profile.result-consumer.unclassified-max-attempts",
                Integer.class,
                2
        );

        Assert.isTrue(
                resumeLimit >= 1 && resumeLimit <= 10,
                "자동 재개 상한은 1~10회여야 합니다."
        );

        Assert.isTrue(
                unknownLimit >= 1 && unknownLimit <= 10,
                "미분류 시도 상한은 1~10회여야 합니다."
        );

        var lifecycle = new ProfilingResultListener(
                container,
                admin,
                probe,
                executor,
                () -> 30000
                        + ThreadLocalRandom.current()
                        .nextLong(-5000, 5001),
                meters,
                clock,
                resumeLimit
        );

        container.setMessageListener(
                new ProfilingResultMessageListener(
                        reader,
                        service,
                        lifecycle,
                        meters,
                        unknownLimit,
                        lifecycle::awaitRetry
                )
        );

        container.setErrorHandler(
                exception -> lifecycle.halt(
                        ProfilingResultListener.Reason.UNCLASSIFIED
                )
        );

        return lifecycle;
    }

    @Bean(destroyMethod = "close")
    public ProfilingResultControl profilingResultControl(
            ProfilingResultListener listener
    ) throws Exception {
        String instanceId = environment.getProperty(
                "app.instance-id",
                ManagementFactory.getRuntimeMXBean().getName()
        );

        return new ProfilingResultControl(listener, instanceId);
    }

    @Bean
    public ProfilingResultBacklog profilingResultBacklog(
            DataSource dataSource,
            MeterRegistry meters,
            Clock clock
    ) {
        return new ProfilingResultBacklog(
                dataSource,
                meters,
                clock
        );
    }
}
