package com.gift.gift.domain.recommendation.chat.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatConversationTokenProperties.class)
public class ChatConversationTokenConfig {
}
