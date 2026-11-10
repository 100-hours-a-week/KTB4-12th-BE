package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.ai-profile.amqp",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(ProfilingAmqpProperties.class)
public class ProfilingAmqpConfiguration {

    public static final String EXCHANGE = "profiling.exchange";

    public static final String REQUEST_QUEUE = "profiling.request";
    public static final String RESULT_QUEUE = "profiling.result";

    public static final String REQUEST_ROUTING_KEY = REQUEST_QUEUE;
    public static final String RESULT_ROUTING_KEY = RESULT_QUEUE;

    public static final String REQUEST_FAILED_QUEUE =
            "profiling.request.failed";
    public static final String RESULT_FAILED_QUEUE =
            "profiling.result.failed";

    @Bean
    public RabbitTemplate profilingRabbitTemplate(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory,
            ProfilingPublisherState publisherState
    ) {
        connectionFactory.getPublisherConnectionFactory()
                .addConnectionListener(publisherState);

        RabbitTemplate template = new RabbitTemplate(connectionFactory);

        template.setUsePublisherConnection(true);
        template.setMandatory(true);

        template.setBeforePublishPostProcessors(message -> {
            MessageProperties properties = message.getMessageProperties();

            properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            properties.setContentEncoding(StandardCharsets.UTF_8.name());

            return message;
        });

        return template;
    }

    @Bean
    public RabbitAdmin profilingRabbitAdmin(
            @Qualifier("rabbitConnectionFactory")
            CachingConnectionFactory connectionFactory
    ) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);

        admin.setAutoStartup(true);
        admin.setExplicitDeclarationsOnly(true);

        return admin;
    }

    @Bean
    public Declarables profilingTopology(
            ProfilingAmqpProperties properties,
            @Qualifier("profilingRabbitAdmin")
            RabbitAdmin admin
    ) {
        DirectExchange exchange = new DirectExchange(
                EXCHANGE,
                true,
                false
        );

        exchange.setAdminsThatShouldDeclare(admin);

        List<Declarable> declarations = new ArrayList<>();
        declarations.add(exchange);

        for (String queueName : List.of(
                REQUEST_QUEUE,
                RESULT_QUEUE,
                REQUEST_FAILED_QUEUE,
                RESULT_FAILED_QUEUE
        )) {
            Queue queue = QueueBuilder
                    .durable(queueName)
                    .quorum()
                    .withArgument(
                            "x-quorum-initial-group-size",
                            properties.initialGroupSize()
                    )
                    .build();

            Binding binding = BindingBuilder
                    .bind(queue)
                    .to(exchange)
                    .with(queueName);

            queue.setAdminsThatShouldDeclare(admin);
            binding.setAdminsThatShouldDeclare(admin);

            declarations.add(queue);
            declarations.add(binding);
        }

        return new Declarables(declarations);
    }

    @Bean
    public ApplicationRunner profilingTopologyInitializer(
            @Qualifier("profilingRabbitAdmin")
            RabbitAdmin admin
    ) {
        return args -> admin.initialize();
    }
}
