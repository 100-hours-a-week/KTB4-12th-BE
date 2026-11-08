package com.gift.gift.domain.order.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "app.payment.mq.topology",
        name = "enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PaymentRabbitTopologyProperties.class)
public class PaymentRabbitTopologyConfiguration {

    public static final String EVENTS_EXCHANGE = "payment.events";

    public static final String ORDER_COMPLETED_ROUTING_KEY = "payment.order.completed";
    public static final String ORDER_CANCELED_ROUTING_KEY = "payment.order.canceled";
    public static final String APPROVAL_RECOVERY_ROUTING_KEY = "payment.approval.recovery";
    public static final String CANCELLATION_RECOVERY_ROUTING_KEY = "payment.cancellation.recovery";

    public static final String RECEIPT_QUEUE = "gift.receipt.email.q";
    public static final String RECEIPT_DLX = "gift.receipt.email.dlx";
    public static final String RECEIPT_DLQ = "gift.receipt.email.dlq";

    public static final String ROULETTE_QUEUE = "gift.roulette.q";
    public static final String ROULETTE_DLX = "gift.roulette.dlx";
    public static final String ROULETTE_DLQ = "gift.roulette.dlq";

    public static final String COUPON_RESTORE_QUEUE = "gift.coupon.restore.q";
    public static final String COUPON_RESTORE_DLX = "gift.coupon.restore.dlx";
    public static final String COUPON_RESTORE_DLQ = "gift.coupon.restore.dlq";

    public static final String APPROVAL_RECOVERY_QUEUE = "gift.payment.approval.recovery.q";
    public static final String APPROVAL_RECOVERY_DLX = "gift.payment.approval.recovery.dlx";
    public static final String APPROVAL_RECOVERY_DLQ = "gift.payment.approval.recovery.dlq";

    public static final String CANCELLATION_RECOVERY_QUEUE = "gift.payment.cancellation.recovery.q";
    public static final String CANCELLATION_RECOVERY_DLX = "gift.payment.cancellation.recovery.dlx";
    public static final String CANCELLATION_RECOVERY_DLQ = "gift.payment.cancellation.recovery.dlq";

    @Bean
    public RabbitAdmin paymentRabbitAdmin(
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory
    ) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        admin.setAutoStartup(true);
        admin.setExplicitDeclarationsOnly(true);
        return admin;
    }

    @Bean
    public Declarables paymentRabbitTopology(
            PaymentRabbitTopologyProperties properties,
            @Qualifier("paymentRabbitAdmin") RabbitAdmin admin
    ) {
        DirectExchange events = directExchange(EVENTS_EXCHANGE, admin);
        DirectExchange receiptDlx = directExchange(RECEIPT_DLX, admin);
        DirectExchange rouletteDlx = directExchange(ROULETTE_DLX, admin);
        DirectExchange couponRestoreDlx = directExchange(COUPON_RESTORE_DLX, admin);
        DirectExchange approvalRecoveryDlx = directExchange(APPROVAL_RECOVERY_DLX, admin);
        DirectExchange cancellationRecoveryDlx = directExchange(CANCELLATION_RECOVERY_DLX, admin);

        int initialGroupSize = properties.initialGroupSize();

        Queue receiptQueue = quorumQueue(RECEIPT_QUEUE, initialGroupSize, admin);
        Queue rouletteQueue = quorumQueue(ROULETTE_QUEUE, initialGroupSize, admin);
        Queue couponRestoreQueue = quorumQueue(COUPON_RESTORE_QUEUE, initialGroupSize, admin);
        Queue approvalRecoveryQueue = quorumQueue(APPROVAL_RECOVERY_QUEUE, initialGroupSize, admin);
        Queue cancellationRecoveryQueue = quorumQueue(CANCELLATION_RECOVERY_QUEUE, initialGroupSize, admin);

        Queue receiptDlq = quorumQueue(RECEIPT_DLQ, initialGroupSize, admin);
        Queue rouletteDlq = quorumQueue(ROULETTE_DLQ, initialGroupSize, admin);
        Queue couponRestoreDlq = quorumQueue(COUPON_RESTORE_DLQ, initialGroupSize, admin);
        Queue approvalRecoveryDlq = quorumQueue(APPROVAL_RECOVERY_DLQ, initialGroupSize, admin);
        Queue cancellationRecoveryDlq = quorumQueue(CANCELLATION_RECOVERY_DLQ, initialGroupSize, admin);

        return new Declarables(
                events,
                receiptDlx,
                rouletteDlx,
                couponRestoreDlx,
                approvalRecoveryDlx,
                cancellationRecoveryDlx,

                receiptQueue,
                rouletteQueue,
                couponRestoreQueue,
                approvalRecoveryQueue,
                cancellationRecoveryQueue,
                receiptDlq,
                rouletteDlq,
                couponRestoreDlq,
                approvalRecoveryDlq,
                cancellationRecoveryDlq,

                binding(receiptQueue, events, ORDER_COMPLETED_ROUTING_KEY, admin),
                binding(rouletteQueue, events, ORDER_COMPLETED_ROUTING_KEY, admin),
                binding(receiptQueue, events, ORDER_CANCELED_ROUTING_KEY, admin),
                binding(couponRestoreQueue, events, ORDER_CANCELED_ROUTING_KEY, admin),
                binding(approvalRecoveryQueue, events, APPROVAL_RECOVERY_ROUTING_KEY, admin),
                binding(cancellationRecoveryQueue, events, CANCELLATION_RECOVERY_ROUTING_KEY, admin),

                binding(receiptDlq, receiptDlx, ORDER_COMPLETED_ROUTING_KEY, admin),
                binding(receiptDlq, receiptDlx, ORDER_CANCELED_ROUTING_KEY, admin),
                binding(receiptDlq, receiptDlx, RECEIPT_QUEUE, admin),
                binding(rouletteDlq, rouletteDlx, ORDER_COMPLETED_ROUTING_KEY, admin),
                binding(rouletteDlq, rouletteDlx, ROULETTE_QUEUE, admin),
                binding(couponRestoreDlq, couponRestoreDlx, ORDER_CANCELED_ROUTING_KEY, admin),
                binding(couponRestoreDlq, couponRestoreDlx, COUPON_RESTORE_QUEUE, admin),
                binding(approvalRecoveryDlq, approvalRecoveryDlx, APPROVAL_RECOVERY_ROUTING_KEY, admin),
                binding(approvalRecoveryDlq, approvalRecoveryDlx, APPROVAL_RECOVERY_QUEUE, admin),
                binding(cancellationRecoveryDlq, cancellationRecoveryDlx, CANCELLATION_RECOVERY_ROUTING_KEY, admin),
                binding(cancellationRecoveryDlq, cancellationRecoveryDlx, CANCELLATION_RECOVERY_QUEUE, admin)
        );
    }

    @Bean
    public ApplicationRunner paymentTopologyInitializer(
            @Qualifier("paymentRabbitAdmin") RabbitAdmin admin
    ) {
        return args -> admin.initialize();
    }

    private DirectExchange directExchange(String name, RabbitAdmin admin) {
        DirectExchange exchange = new DirectExchange(name, true, false);
        exchange.setAdminsThatShouldDeclare(admin);
        return exchange;
    }

    private Queue quorumQueue(String name, int initialGroupSize, RabbitAdmin admin) {
        Queue queue = QueueBuilder.durable(name)
                .quorum()
                .withArgument("x-quorum-initial-group-size", initialGroupSize)
                .build();
        queue.setAdminsThatShouldDeclare(admin);
        return queue;
    }

    private Binding binding(Queue queue, DirectExchange exchange, String routingKey, RabbitAdmin admin) {
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(routingKey);
        binding.setAdminsThatShouldDeclare(admin);
        return binding;
    }
}
