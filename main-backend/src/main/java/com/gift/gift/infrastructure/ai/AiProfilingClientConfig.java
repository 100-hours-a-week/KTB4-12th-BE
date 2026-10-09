package com.gift.gift.infrastructure.ai;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.support.AiProfilingProperties;

@Configuration
@EnableConfigurationProperties(AiProfilingProperties.class)
public class AiProfilingClientConfig {

    @Bean
    public RestClient aiProfilingRestClient(
            AiProfilingProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.readTimeout());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.serviceToken()
                )
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }

    @Bean
    public RestClient aiProfilingHealthRestClient(
            AiProfilingProperties properties
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        Duration limit = Duration.ofSeconds(2);

        // 기존 설정이 더 짧으면 해당 값을 유지한다.
        requestFactory.setConnectTimeout(
                properties.connectTimeout().compareTo(limit) < 0
                        ? properties.connectTimeout()
                        : limit
        );
        requestFactory.setReadTimeout(
                properties.readTimeout().compareTo(limit) < 0
                        ? properties.readTimeout()
                        : limit
        );

        // 무인증 /health에는 서비스 토큰을 넣지 않는다.
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }
}
