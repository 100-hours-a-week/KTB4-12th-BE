package com.gift.gift.domain.bugreport.support;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * 웹훅 URL 미설정 시에도 애플리케이션은 정상 기동해야 하므로
 * 다른 Properties와 달리 canonical constructor에서 null을 거부하지 않는다.
 */
@ConfigurationProperties(prefix = "app.bug-report")
public record BugReportWebhookProperties(String webhookUrl) {

    public boolean isConfigured() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }
}
