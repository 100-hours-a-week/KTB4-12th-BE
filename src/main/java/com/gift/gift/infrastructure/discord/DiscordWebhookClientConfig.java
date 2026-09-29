package com.gift.gift.infrastructure.discord;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class DiscordWebhookClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient discordWebhookRestClient() {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(
                (int) CONNECT_TIMEOUT.toMillis()
        );
        requestFactory.setReadTimeout(
                (int) READ_TIMEOUT.toMillis()
        );

        return RestClient.builder()
                .requestFactory(requestFactory)
                .build();
    }
}
