package com.gift.gift.domain.bugreport.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.report.mq")
public record ReportMqProperties(
        @DefaultValue("10") @Min(1) @Max(1000) int batchSize
) {
    public static final String EVENT_TYPE = "report.created.v1";
    public static final String EVENTS = "report.events";
    public static final String RETRY = "report.retry";
    public static final String DLX = "report.dlx";
    public static final String DISCORD_QUEUE = "Report.discord";
    public static final String SHEETS_QUEUE = "Report.sheets";
    public static final String RETRY_QUEUE = "Report.retry";
    public static final String DLQ = "Report.dlq";
    public static final String CREATED = "Report.created";
    public static final String DISCORD = "Report.discord.process";
    public static final String SHEETS = "Report.sheets.process";
}
