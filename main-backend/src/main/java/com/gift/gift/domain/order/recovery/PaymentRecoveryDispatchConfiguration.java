package com.gift.gift.domain.order.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        name = "app.payment.recovery.dispatch.enabled",
        havingValue = "true"
)
@EnableConfigurationProperties(PaymentRecoveryDispatchProperties.class)
public class PaymentRecoveryDispatchConfiguration {
}
