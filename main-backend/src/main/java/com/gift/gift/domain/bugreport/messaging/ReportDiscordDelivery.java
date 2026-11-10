package com.gift.gift.domain.bugreport.messaging;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.infrastructure.discord.DiscordWebhookClient;

public record ReportDiscordDelivery(ObjectMapper mapper, DiscordWebhookClient discord, String webhookUrl) {
    public ReportDeliveryResult deliver(JsonNode report) {
        String id = report.path("reportId").asText();
        String description = report.path("description").asText();
        var payload = mapper.createObjectNode();
        payload.putObject("allowed_mentions").putArray("parse");
        var embed = payload.putArray("embeds").addObject();
        embed.put("title", "서비스 제보 · " + truncate(report.path("category").asText(), 100));
        embed.put("description", truncate(description, 4096));
        var fields = embed.putArray("fields");
        fields.addObject().put("name", "reportId").put("value", id);
        for (String field : new String[]{"pageUrl", "route", "viewport", "userAgent", "reportedAt"}) {
            String value = report.path(field).asText("");
            if (!value.isBlank()) fields.addObject().put("name", field).put("value", truncate(value, 240));
        }
        String reporter = report.path("userId").isNull() ? "익명"
            : "사용자 ID: " + report.path("userId").asText() + "\n이메일: " + report.path("email").asText();
        fields.addObject().put("name", "제보자").put("value", truncate(reporter, 400));
        return discord.send(webhookUrl, mapper.writeValueAsString(payload));
    }

    private String truncate(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 2)) ? limit - 2 : limit - 1;
        return text.substring(0, end) + "…";
    }

}
