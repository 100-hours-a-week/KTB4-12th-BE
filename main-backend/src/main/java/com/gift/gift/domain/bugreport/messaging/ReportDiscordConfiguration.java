package com.gift.gift.domain.bugreport.messaging;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.global.messaging.RabbitManagementConfiguration;
import com.gift.gift.global.messaging.RabbitManagementProperties;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.report.discord.enabled", havingValue = "true")
@Import(RabbitManagementConfiguration.class)
public class ReportDiscordConfiguration {
    @Bean
    public ReportDiscordListener reportDiscordListener(
        @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connection,
        @Qualifier("rabbitManagementRestClient") RestClient managementClient,
        RabbitManagementProperties management, BugReportWebhookProperties webhook,
        @Qualifier("stringRedisTemplate") StringRedisTemplate redis,
        ObjectMapper mapper, DiscordWebhookClient discord, RabbitPublisher publisher, MeterRegistry meters) {
        Assert.isTrue(webhook.isConfigured(), "Discord consumer requires a webhook URL");
        var uri = java.net.URI.create(webhook.webhookUrl());
        Assert.isTrue(("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null,
            "Discord consumer requires an absolute HTTP webhook URL");
        var container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(ReportMqProperties.DISCORD_QUEUE);
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        container.setConcurrentConsumers(1);
        container.setPrefetchCount(1);
        container.setAutoDeclare(false);
        container.setMissingQueuesFatal(false);
        container.setAutoStartup(false);
        container.setShutdownTimeout(30000);
        container.setForceCloseChannel(true);
        container.setForceStop(true);
        var backoff = new ExponentialBackOff(5000, 2);
        backoff.setMaxInterval(60000);
        container.setRecoveryBackOff(backoff);
        var listener = new ReportDiscordListener(container,
            new ReportTopologyPreflight(managementClient, management.url(), connection.getVirtualHost()));
        container.setMessageListener(new ReportDiscordConsumer(mapper, discord, webhook.webhookUrl(),
            new ReportDiscordCooldown(redis, webhook.webhookUrl()), publisher, meters,
            listener::recover, listener::recovered));
        return listener;
    }
}
