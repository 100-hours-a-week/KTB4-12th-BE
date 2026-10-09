package com.gift.gift.infrastructure.google;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class GoogleApiClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @Qualifier("googleApiRestClient")
    public RestClient googleApiRestClient() {
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
