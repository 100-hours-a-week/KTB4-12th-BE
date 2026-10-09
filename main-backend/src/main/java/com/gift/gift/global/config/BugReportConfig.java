package com.gift.gift.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import com.gift.gift.domain.bugreport.support.BugReportGoogleSheetsProperties;
import com.gift.gift.domain.bugreport.support.BugReportWebhookProperties;

@Configuration
@EnableConfigurationProperties({
        BugReportWebhookProperties.class,
        BugReportGoogleSheetsProperties.class
})
public class BugReportConfig {
}
