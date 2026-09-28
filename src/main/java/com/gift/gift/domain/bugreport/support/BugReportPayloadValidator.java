package com.gift.gift.domain.bugreport.support;

import java.nio.charset.StandardCharsets;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.bugreport.dto.BugReportPayload;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;

@Component
@RequiredArgsConstructor
public class BugReportPayloadValidator {

    private static final int MAX_PAYLOAD_BYTES = 10 * 1024;

    private final ObjectMapper objectMapper;

    public void validate(String payloadJson) {
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
