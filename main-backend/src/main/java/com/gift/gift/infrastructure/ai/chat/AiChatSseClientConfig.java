package com.gift.gift.infrastructure.ai.chat;

import java.net.http.HttpClient;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.gift.gift.domain.recommendation.chat.config.ChatSseProperties;

@Configuration
@EnableConfigurationProperties({
        AiChatProperties.class,
        ChatSseProperties.class
})
public class AiChatSseClientConfig {

    @Bean(destroyMethod = "shutdownNow")
    public HttpClient aiChatSseHttpClient(
            AiChatProperties properties
    ) {
        return HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chatSseIdleScheduler() {
        return Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform()
                        .daemon(true)
                        .name("chat-sse-idle")
                        .factory()
        );
    }

    @Bean
    public RestClient aiChatSseRestClient(
            AiChatProperties properties,
            ChatSseProperties sse,
            @Qualifier("aiChatSseHttpClient") HttpClient httpClient
    ) {
        JdkClientHttpRequestFactory factory =
                new JdkClientHttpRequestFactory(httpClient);

        // 이 factory의 timeout은 응답 전체 기한이다.
        factory.setReadTimeout(sse.totalTimeout());
        factory.enableCompression(false);

        return RestClient.builder()
                .baseUrl(
                        properties.baseUrl()
                                .toString()
                                .replaceAll("/+$", "")
                )
                .requestFactory(factory)
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + properties.serviceToken()
                )
                .defaultHeader(
                        HttpHeaders.ACCEPT,
                        MediaType.TEXT_EVENT_STREAM_VALUE
                )
                .defaultHeader(
                        HttpHeaders.ACCEPT_ENCODING,
                        "identity"
                )
                .build();
    }
}
