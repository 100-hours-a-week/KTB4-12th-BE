package com.gift.gift.domain.bugreport.support;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.bugreport.exception.BugReportException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BugReportPayloadValidatorTest {

    private final BugReportPayloadValidator validator =
            new BugReportPayloadValidator(JsonMapper.builder().build());

    @Test
    @DisplayName("payload_json이 비어 있으면 예외를 발생시킨다")
    void validate_throws_whenPayloadBlank() {
        assertThatThrownBy(() -> validator.validate(""))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("payload_json이 10KB를 초과하면 예외를 발생시킨다")
    void validate_throws_whenPayloadExceeds10KB() {
        String hugeDescription = "a".repeat(11 * 1024);
        String payload = """
                {"embeds":[{"description":"%s"}]}
                """.formatted(hugeDescription);

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("payload_json이 JSON 형식이 아니면 예외를 발생시킨다")
    void validate_throws_whenPayloadNotValidJson() {
        assertThatThrownBy(() -> validator.validate("not-json"))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("embeds가 비어 있으면 예외를 발생시킨다")
    void validate_throws_whenEmbedsEmpty() {
        assertThatThrownBy(() -> validator.validate("{\"embeds\":[]}"))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("첫 embed의 description이 비어 있으면 예외를 발생시킨다")
    void validate_throws_whenDescriptionBlank() {
        String payload = "{\"embeds\":[{\"description\":\"   \"}]}";

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(BugReportException.class);
    }

    @Test
    @DisplayName("description이 채워진 유효한 payload는 통과한다")
    void validate_passes_forValidPayload() {
        String payload = "{\"embeds\":[{\"description\":\"버그 설명\"}]}";

        assertThatCode(() -> validator.validate(payload))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("인증 사용자의 ID와 이메일을 Discord embed에 추가한다")
    void addReporter_addsUserIdAndEmailToEmbed() {
        String payload = "{\"embeds\":[{\"description\":\"버그 설명\"}]}";

        String enriched = validator.addReporter(
                payload,
                1L,
                "user@example.com"
        );

        assertThat(enriched)
                .contains("제보자")
                .contains("사용자 ID: 1")
                .contains("이메일: user@example.com");
    }

    @Test
    @DisplayName("비로그인 제보는 Discord embed에 익명으로 표시한다")
    void addReporter_marksAnonymousReporter() {
        String payload = "{\"embeds\":[{\"description\":\"버그 설명\"}]}";

        String enriched = validator.addReporter(payload, null, "");

        assertThat(enriched).contains("제보자").contains("익명");
    }
}
