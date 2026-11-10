package com.gift.gift.domain.bugreport.messaging;

import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

import com.gift.gift.global.outbox.OutboxRelay;
import com.gift.gift.global.messaging.RabbitManagementConfiguration;
import com.gift.gift.global.messaging.RabbitManagementProperties;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.report.mq.enabled", havingValue = "true")
@EnableConfigurationProperties(ReportMqProperties.class)
@Import(RabbitManagementConfiguration.class)
public class ReportMqConfiguration {
    @Bean
    public ReportTopologyPreflight reportTopologyPreflight(RabbitManagementProperties management,
            @Qualifier("rabbitManagementRestClient") RestClient client, Environment environment,
            @Qualifier("rabbitConnectionFactory") CachingConnectionFactory connectionFactory) {
        Assert.isTrue(environment.getProperty("app.outbox.enabled", Boolean.class, false),
                "리포트 MQ 발행에는 app.outbox.enabled=true가 필요합니다.");
        return new ReportTopologyPreflight(client, management.url(), connectionFactory.getVirtualHost());
    }

    @Bean
    public ReportOutboxRelay reportOutboxRelay(OutboxRelay relay, ReportTopologyPreflight preflight,
                                              ReportMqProperties settings) {
        return new ReportOutboxRelay(relay, preflight, settings);
    }
}
