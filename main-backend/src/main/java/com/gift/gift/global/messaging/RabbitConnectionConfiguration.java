package com.gift.gift.global.messaging;

import com.rabbitmq.client.impl.nio.NioParams;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.amqp.autoconfigure.ConnectionFactoryCustomizer;
import org.springframework.boot.amqp.health.RabbitHealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.Assert;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.outbox.enabled:false} or ${app.ai-profile.amqp.enabled:false}")
public class RabbitConnectionConfiguration {

    @Bean
    public ConnectionFactoryCustomizer rabbitClientIoLimits() {
        return client -> {
            client.setHandshakeTimeout(3000);
            client.setChannelRpcTimeout(3000);
            client.setShutdownTimeout(3000);
            client.setNioParams(new NioParams()
                    .setWriteQueueCapacity(64)
                    .setWriteEnqueuingTimeoutInMs(3000));
            client.useNio();
        };
    }

    @Bean
    public static BeanPostProcessor rabbitConnectionLimits() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if ("rabbitConnectionFactory".equals(beanName)
                        && bean instanceof CachingConnectionFactory factory) {
                    var client = factory.getRabbitConnectionFactory();
                    Assert.hasText(client.getHost(), "spring.rabbitmq.host는 필수입니다.");
                    Assert.isTrue(client.getPort() >= 1 && client.getPort() <= 65535,
                            "spring.rabbitmq.port는 1~65535 범위여야 합니다.");
                    Assert.hasText(client.getUsername(), "spring.rabbitmq.username은 필수입니다.");
                    Assert.hasText(client.getPassword(), "spring.rabbitmq.password는 필수입니다.");
                    Assert.hasText(client.getVirtualHost(), "spring.rabbitmq.virtual-host는 필수입니다.");
                    // Boot의 접속·TLS 설정은 보존하고 공유 연결의 대기 제한만 보완한다.
                    factory.setChannelCheckoutTimeout(3000);
                    factory.setCloseTimeout(3000);
                    factory.setConnectionNameStrategy(connectionFactory -> "gift-be");
                }
                return bean;
            }
        };
    }

    @Bean
    public RabbitHealthIndicator rabbitHealthIndicator(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory) {
        return new RabbitHealthIndicator(new RabbitTemplate(connectionFactory));
    }
}
