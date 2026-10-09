package com.gift.gift.domain.bugreport.support;

import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import com.gift.gift.domain.bugreport.dto.BugReportPayload;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;

@Component
@RequiredArgsConstructor
public class BugReportPayloadValidator {

    private static final int MAX_PAYLOAD_BYTES = 10 * 1024;

    private final ObjectMapper objectMapper;

    public BugReportPayload validate(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new BugReportException(
                    BugReportErrorCode.PAYLOAD_REQUIRED
            );
        }

        if (payloadJson.getBytes(StandardCharsets.UTF_8).length
                > MAX_PAYLOAD_BYTES) {
            throw new BugReportException(
                    BugReportErrorCode.PAYLOAD_TOO_LARGE
            );
        }

        BugReportPayload payload = parse(payloadJson);

        if (!hasDescription(payload)) {
            throw new BugReportException(
                    BugReportErrorCode.DESCRIPTION_REQUIRED
            );
        }

        return payload;
    }

    public String addReporter(
            String payloadJson,
            Long userId,
            String email
    ) {
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(payloadJson);
            ObjectNode embed = (ObjectNode) root.path("embeds").get(0);
            ArrayNode fields = embed.withArrayProperty("fields");

            removeClientReporter(fields);

            ObjectNode reporter = objectMapper.createObjectNode();
            reporter.put("name", "제보자");
            reporter.put("value", reporterValue(userId, email));
            reporter.put("inline", false);
            fields.add(reporter);

            return objectMapper.writeValueAsString(root);
        } catch (JacksonException | ClassCastException exception) {
            throw new BugReportException(
                    BugReportErrorCode.PAYLOAD_INVALID_FORMAT
            );
        }
    }

    private void removeClientReporter(ArrayNode fields) {
        for (int index = fields.size() - 1; index >= 0; index--) {
            JsonNode field = fields.get(index);
            if ("제보자".equals(field.path("name").asString())) {
                fields.remove(index);
            }
        }
    }

    private String reporterValue(Long userId, String email) {
        if (userId == null) {
            return "익명";
        }
        return "사용자 ID: " + userId + "\n이메일: " + email;
    }

    private BugReportPayload parse(String payloadJson) {
        try {
            return objectMapper.readValue(
                    payloadJson,
                    BugReportPayload.class
            );
        } catch (JacksonException exception) {
            throw new BugReportException(
                    BugReportErrorCode.PAYLOAD_INVALID_FORMAT
            );
        }
    }

    private boolean hasDescription(BugReportPayload payload) {
        List<BugReportPayload.Embed> embeds = payload.embeds();

        if (embeds == null || embeds.isEmpty()) {
            return false;
        }

        String description = embeds.get(0).description();

        return description != null && !description.isBlank();
    }
}
