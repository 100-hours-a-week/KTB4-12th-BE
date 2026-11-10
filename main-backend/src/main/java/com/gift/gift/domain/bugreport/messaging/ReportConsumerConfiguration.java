package com.gift.gift.domain.bugreport.messaging;

import java.net.URI;
import java.util.function.Function;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.Assert;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;
import com.gift.gift.global.messaging.RabbitManagementConfiguration;
import com.gift.gift.global.messaging.RabbitManagementProperties;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.infrastructure.discord.DiscordWebhookClient;
import com.gift.gift.infrastructure.google.GoogleBugReportClient;
import com.gift.gift.infrastructure.google.GoogleServiceAccountTokenProvider;

@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("${app.report.discord.enabled:false} or ${app.report.sheets.enabled:false}")
@Import(RabbitManagementConfiguration.class)
public class ReportConsumerConfiguration {
    @Bean
    @ConditionalOnProperty(name = "app.report.discord.enabled", havingValue = "true")
    public ReportConsumerListener reportDiscordListener(
        @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connection,
        @Qualifier("rabbitManagementRestClient") RestClient managementClient,
        RabbitManagementProperties management, BugReportWebhookProperties webhook,
        @Qualifier("stringRedisTemplate") StringRedisTemplate redis,
        ObjectMapper mapper, DiscordWebhookClient discord, RabbitPublisher publisher, MeterRegistry meters) {
        Assert.isTrue(webhook.isConfigured(), "Discord consumer requires a webhook URL");
        URI uri = URI.create(webhook.webhookUrl());
        Assert.isTrue(("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) && uri.getHost() != null,
            "Discord consumer requires an absolute HTTP webhook URL");
        return listener("discord", ReportMqProperties.DISCORD, ReportMqProperties.DISCORD_QUEUE,
            new ReportDiscordDelivery(mapper, discord, webhook.webhookUrl())::deliver,
            new ReportCooldown(redis, "discord", uri.getAuthority() + uri.getPath(), "global"),
            connection, managementClient, management, mapper, publisher, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "app.report.sheets.enabled", havingValue = "true")
    public ReportConsumerListener reportSheetsListener(
        @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connection,
        @Qualifier("rabbitManagementRestClient") RestClient managementClient,
        RabbitManagementProperties management, BugReportGoogleSheetsProperties sheets,
        @Qualifier("stringRedisTemplate") StringRedisTemplate redis,
        ObjectMapper mapper, GoogleBugReportClient google, GoogleServiceAccountTokenProvider tokens,
        RabbitPublisher publisher, MeterRegistry meters) {
        Assert.isTrue(sheets.isConfigured(), "Sheets consumer requires credentials, spreadsheet and tab");
        return listener("sheets", ReportMqProperties.SHEETS, ReportMqProperties.SHEETS_QUEUE, google::deliver,
            new ReportCooldown(redis, "sheets", tokens.accountScope(), tokens.projectScope()),
            connection, managementClient, management, mapper, publisher, meters);
    }

    private ReportConsumerListener listener(String channelName, String routingKey, String queue,
        Function<JsonNode, ReportDeliveryResult> delivery, ReportCooldown cooldown,
        CachingConnectionFactory connection, RestClient managementClient, RabbitManagementProperties management,
        ObjectMapper mapper, RabbitPublisher publisher, MeterRegistry meters) {
        var container = new SimpleMessageListenerContainer(connection);
        container.setQueueNames(queue);
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
        var listener = new ReportConsumerListener(container,
            new ReportTopologyPreflight(managementClient, management.url(), connection.getVirtualHost()), channelName);
        container.setMessageListener(new ReportConsumer(mapper, channelName, routingKey, queue, delivery, cooldown,
            publisher, meters, listener::recover, listener::recovered));
        return listener;
    }
}
