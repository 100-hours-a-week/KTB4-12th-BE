package com.gift.payment.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.gift.payment.global.security.InternalApiKeyProperties;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(InternalApiKeyProperties.class)
public class InternalApiSecurityConfig {
}
