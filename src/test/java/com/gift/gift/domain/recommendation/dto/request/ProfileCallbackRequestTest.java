package com.gift.gift.domain.recommendation.dto.request;

import jakarta.validation.Validator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.support.TestValidatorFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProfileCallbackRequestTest {

    private ObjectMapper objectMapper;
    private LocalValidatorFactoryBean validatorFactory;
    private Validator validator;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validatorFactory = TestValidatorFactory.create();
        validator = validatorFactory.getValidator();
    }

    @AfterEach
    void tearDown() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[105,32,981]", "[1]"})
    @DisplayName("빈 배열을 포함한 정상 콜백은 검증을 통과한다")
    void request_acceptsValidProductIds(String productIds) {
        ProfileCallbackRequest request = read(body("10", "0", "\"COMPLETED\"", productIds));
        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "null"})
    @DisplayName("수신자 ID가 양수가 아니거나 null이면 거부한다")
    void request_rejectsInvalidRecipientId(String recipientId) {
        assertInvalid(body(recipientId, "1", "\"COMPLETED\"", "[]"), "recipientUserId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "null"})
    @DisplayName("버전이 음수이거나 null이면 거부한다")
    void request_rejectsInvalidSourceVersion(String sourceVersion) {
        assertInvalid(body("10", sourceVersion, "\"COMPLETED\"", "[]"), "sourceVersion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"PENDING\"", "\"FAILED\"", "\"NONE\"", "\"completed\"", "\"\"", "null"})
    @DisplayName("COMPLETED 이외 상태와 null 상태는 거부한다")
    void request_rejectsInvalidStatus(String status) {
        assertInvalid(body("10", "1", status, "[]"), "profileStatus");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[0]", "[-1]", "[null]", "[105,105]", "null"})
    @DisplayName("추천 상품의 범위·null·중복 오류는 거부한다")
    void request_rejectsInvalidProductIds(String productIds) {
        assertInvalid(body("10", "1", "\"COMPLETED\"", productIds), "recommendedProductIds");
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 31})
    @DisplayName("추천 상품은 30개까지 허용하고 31개는 거부한다")
    void request_checksProductCountBoundary(int count) {
        String productIds = java.util.stream.LongStream.rangeClosed(1, count)
                .mapToObj(Long::toString)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        ProfileCallbackRequest request = read(body("10", "1", "\"COMPLETED\"", productIds));
        assertThat(validator.validate(request).isEmpty()).isEqualTo(count == 30);
    }

    @ParameterizedTest
    @ValueSource(strings = {"recipientUserId", "sourceVersion", "profileStatus", "recommendedProductIds"})
    @DisplayName("필수 필드를 생략하면 거부한다")
    void request_rejectsMissingField(String field) {
        var body = objectMapper.createObjectNode();
        body.put("recipientUserId", 10);
        body.put("sourceVersion", 1);
        body.put("profileStatus", "COMPLETED");
        body.putArray("recommendedProductIds");
        body.remove(field);
        assertInvalid(body.toString(), field);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"10\"", "1.5", "true", "{}", "[]", "9223372036854775808"})
    @DisplayName("ID·버전은 문자열·소수·다른 타입·Long 범위 초과를 거부한다")
    void request_rejectsWrongIntegerTypes(String value) {
        String reason = "9223372036854775808".equals(value)
                ? "OUT_OF_RANGE"
                : "INVALID_TYPE";

        assertInvalid(
                body(value, "1", "\"COMPLETED\"", "[]"),
                "recipientUserId",
                reason
        );

        assertInvalid(
                body("10", value, "\"COMPLETED\"", "[]"),
                "sourceVersion",
                reason
        );

        assertInvalid(
                body("10", "1", "\"COMPLETED\"", "[" + value + "]"),
                "recommendedProductIds",
                reason
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "{}", "[]"})
    @DisplayName("상태가 JSON 문자열이 아니면 거부한다")
    void request_rejectsWrongStatusType(String status) {
        assertInvalid(
                body("10", "1", status, "[]"),
                "profileStatus",
                "INVALID_TYPE"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "true", "{}", "\"[105]\""})
    @DisplayName("추천 상품 목록이 JSON 배열이 아니면 거부한다")
    void request_rejectsWrongArrayType(String productIds) {
        assertInvalid(
                body("10", "1", "\"COMPLETED\"", productIds),
                "recommendedProductIds",
                "INVALID_TYPE"
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "1", "true", "\"body\""})
    @DisplayName("본문이 JSON 객체가 아니면 거부한다")
    void request_rejectsNonObjectBody(String json) {
        assertUnreadable(json);
    }

    private ProfileCallbackRequest read(String json) {
        return objectMapper.readValue(json, ProfileCallbackRequest.class);
    }

    private void assertInvalid(String json, String field) {
        assertThat(validator.validate(read(json)))
                .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                        .startsWith(field));
    }

    private void assertInvalid(
            String json,
            String field,
            String reason
    ) {
        ProfileCallbackRequest request = read(json);

        assertThat(validator.validate(request))
                .anySatisfy(violation -> {
                    assertThat(violation.getPropertyPath().toString())
                            .isEqualTo(field);

                    assertThat(violation.getMessage())
                            .isEqualTo(reason);
                });
    }

    private void assertUnreadable(String json) {
        assertThatThrownBy(() -> read(json)).isInstanceOf(JacksonException.class);
    }

    private String body(String recipientId, String version, String status, String productIds) {
        return """
                {"recipientUserId":%s,"sourceVersion":%s,
                 "profileStatus":%s,"recommendedProductIds":%s}
                """.formatted(recipientId, version, status, productIds);
    }
}
