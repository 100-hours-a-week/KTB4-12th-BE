package com.gift.gift.global.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;

@EnableConfigurationProperties(RabbitManagementProperties.class)
public class RabbitManagementConfiguration {
    @Bean
    public RestClient rabbitManagementRestClient(RabbitManagementProperties settings) {
        Assert.isTrue("http".equals(settings.url().getScheme())
                        || "https".equals(settings.url().getScheme()),
                "Management URL은 http 또는 https여야 합니다.");
        Assert.isNull(settings.url().getUserInfo(), "Management URL에 인증 정보를 넣지 마세요.");
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return RestClient.builder().requestFactory(factory)
                .defaultHeaders(headers -> headers.setBasicAuth(settings.username(), settings.password()))
                .build();
    }
}
