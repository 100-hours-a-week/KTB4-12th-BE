package com.gift.gift.domain.order.messaging;

import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.amqp.autoconfigure.health.RabbitHealthContributorAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gift.gift.global.messaging.RabbitConnectionConfiguration;

import static com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;

class PaymentRabbitTopologyConfigurationTest {

    private static final Set<String> EXPECTED_BINDINGS = Set.of(
            "payment.events|payment.order.completed|gift.receipt.email.q",
            "payment.events|payment.order.completed|gift.roulette.q",
            "payment.events|payment.order.canceled|gift.receipt.email.q",
            "payment.events|payment.order.canceled|gift.coupon.restore.q",
            "payment.events|payment.approval.recovery|gift.payment.approval.recovery.q",
            "payment.events|payment.cancellation.recovery|gift.payment.cancellation.recovery.q",
            "gift.receipt.email.dlx|payment.order.completed|gift.receipt.email.dlq",
            "gift.receipt.email.dlx|payment.order.canceled|gift.receipt.email.dlq",
            "gift.receipt.email.dlx|gift.receipt.email.q|gift.receipt.email.dlq",
            "gift.roulette.dlx|payment.order.completed|gift.roulette.dlq",
            "gift.roulette.dlx|gift.roulette.q|gift.roulette.dlq",
            "gift.coupon.restore.dlx|payment.order.canceled|gift.coupon.restore.dlq",
            "gift.coupon.restore.dlx|gift.coupon.restore.q|gift.coupon.restore.dlq",
            "gift.payment.approval.recovery.dlx|payment.approval.recovery|gift.payment.approval.recovery.dlq",
            "gift.payment.approval.recovery.dlx|gift.payment.approval.recovery.q|gift.payment.approval.recovery.dlq",
            "gift.payment.cancellation.recovery.dlx|payment.cancellation.recovery|gift.payment.cancellation.recovery.dlq",
            "gift.payment.cancellation.recovery.dlx|gift.payment.cancellation.recovery.q|gift.payment.cancellation.recovery.dlq"
    );

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RabbitAutoConfiguration.class,
                    RabbitHealthContributorAutoConfiguration.class
            ))
            .withUserConfiguration(
                    RabbitConnectionConfiguration.class,
                    PaymentRabbitTopologyConfiguration.class
            )
            .withPropertyValues(
                    "spring.rabbitmq.host=shared-host.invalid",
                    "spring.rabbitmq.port=5678",
                    "spring.rabbitmq.username=shared-user",
                    "spring.rabbitmq.password=test-password",
                    "spring.rabbitmq.virtual-host=shared-vhost",
                    "spring.rabbitmq.dynamic=false",
                    "management.health.rabbit.enabled=false"
            );

    @Test
    @DisplayName("운영 복제본 수 설정으로 명세와 동일한 Exchange, Quorum 큐, 바인딩을 만든다")
    void enabledDeclaresExpectedTopology() {
        runner.withPropertyValues(
                "app.payment.mq.topology.enabled=true",
                "app.payment.mq.topology.initial-group-size=3"
        ).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasBean("paymentRabbitAdmin");
            assertThat(context).hasBean("paymentTopologyInitializer");
            assertThat(context).hasBean("rabbitHealthIndicator");

            Declarables topology = context.getBean("paymentRabbitTopology", Declarables.class);

            assertThat(topology.getDeclarablesByType(DirectExchange.class))
                    .hasSize(6)
                    .allSatisfy(exchange -> {
                        assertThat(exchange.isDurable()).isTrue();
                        assertThat(exchange.isAutoDelete()).isFalse();
                    });

            assertThat(topology.getDeclarablesByType(Queue.class))
                    .hasSize(10)
                    .allSatisfy(queue -> {
                        assertThat(queue.isDurable()).isTrue();
                        assertThat(queue.isExclusive()).isFalse();
                        assertThat(queue.isAutoDelete()).isFalse();
                        assertThat(queue.getArguments())
                                .containsEntry("x-queue-type", "quorum")
                                .containsEntry("x-quorum-initial-group-size", 3)
                                .doesNotContainKeys("x-dead-letter-exchange", "x-message-ttl");
                    });

            Set<String> actualBindings = topology.getDeclarablesByType(Binding.class).stream()
                    .map(binding -> binding.getExchange() + "|"
                            + binding.getRoutingKey() + "|"
                            + binding.getDestination())
                    .collect(Collectors.toSet());

            assertThat(actualBindings).containsExactlyInAnyOrderElementsOf(EXPECTED_BINDINGS);
        });
    }

    @Test
    @DisplayName("초기 복제본 수를 지정하지 않으면 로컬 기본값 1을 사용한다")
    void initialGroupSizeDefaultsToOne() {
        runner.withPropertyValues("app.payment.mq.topology.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PaymentRabbitTopologyProperties.class).initialGroupSize())
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("초기 복제본 수가 0이면 설정 오류로 시작을 거부한다")
    void nonPositiveInitialGroupSizeIsRejected() {
        runner.withPropertyValues(
                "app.payment.mq.topology.enabled=true",
                "app.payment.mq.topology.initial-group-size=0"
        ).run(context -> assertThat(context).hasFailed());
    }
}
