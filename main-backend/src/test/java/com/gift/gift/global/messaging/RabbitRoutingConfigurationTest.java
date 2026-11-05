package com.gift.gift.global.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitRoutingConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitPublisherConfiguration.class)
            .withBean(CachingConnectionFactory.class, CachingConnectionFactory::new);

    @Test
    @DisplayName("비활성 시 라우팅 설정 없이 기동하고 활성 시 미확정 라우팅은 거부한다")
    void requiresRoutingOnlyWhenEnabled() {
        contextRunner.run(context -> assertThat(context).hasNotFailed()
                .doesNotHaveBean(RabbitRoutingProperties.class));
        contextRunner.withPropertyValues("app.outbox.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("라우팅을 설정해도 Exchange와 큐를 자동 선언하지 않는다")
    void configuresPublisherWithoutDeclaringTopology() {
        contextRunner.withPropertyValues("app.outbox.enabled=true",
                        "app.outbox.rabbit.exchange=team.exchange",
                        "app.outbox.rabbit.notification-routing-key=team.notification",
                        "app.outbox.rabbit.search-routing-key=team.search")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasBean("outboxRabbitTemplate");
                    assertThat(context.getBeansOfType(Declarable.class)).isEmpty();
                    assertThat(context.getBeansOfType(Declarables.class)).isEmpty();
                    assertThat(context.getBean(RabbitRoutingProperties.class).exchange()).isEqualTo("team.exchange");
                });
    }
}
