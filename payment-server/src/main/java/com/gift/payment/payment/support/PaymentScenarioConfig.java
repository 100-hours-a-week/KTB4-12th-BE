package com.gift.payment.payment.support;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PaymentScenarioProperties.class)
public class PaymentScenarioConfig {
}
