package com.gift.gift.infrastructure.payment;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.payment.client")
public record PaymentClientProperties(
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration commandTimeout,
        Duration queryTimeout
) {

    public PaymentClientProperties {
        if (!validBaseUrl(baseUrl)
                || !validApiKey(apiKey)
                || !validTimeout(connectTimeout)
                || !validTimeout(commandTimeout)
                || !validTimeout(queryTimeout)) {
            throw new IllegalArgumentException("Payment Server 클라이언트 설정을 확인해 주세요.");
        }
    }

    private static boolean validBaseUrl(URI baseUrl) {
        if (baseUrl == null || baseUrl.getHost() == null) {
            return false;
        }

        return ("http".equals(baseUrl.getScheme())
                || "https".equals(baseUrl.getScheme()))
                && baseUrl.getUserInfo() == null
                && baseUrl.getQuery() == null
                && baseUrl.getFragment() == null;
    }

    private static boolean validApiKey(String apiKey) {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.contains("\r")
                && !apiKey.contains("\n");
    }

    private static boolean validTimeout(Duration timeout) {
        return timeout != null
                && !timeout.isNegative()
                && !timeout.isZero();
    }

    @Override
    public String toString() {
        return "PaymentClientProperties[apiKey=REDACTED]";
    }
}
