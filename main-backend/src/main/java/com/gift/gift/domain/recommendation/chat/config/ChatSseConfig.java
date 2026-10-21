package com.gift.gift.domain.recommendation.chat.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ChatSseProperties.class)
public class ChatSseConfig {

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService chatSseExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("chat-sse-", 0).factory()
        );
    }
}
