package com.gift.gift.domain.user.dto.request;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import tools.jackson.databind.JsonNode;

import com.gift.gift.domain.user.validation.ValidUserProfileUpdate;

@ValidUserProfileUpdate
public class UpdateUserProfileRequest {

    private static final DateTimeFormatter BIRTH_FORMAT =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private JsonNode birth;
    private JsonNode isBirthdayPublic;

    private boolean birthProvided;
    private boolean birthdayPublicProvided;

    private final Map<String, JsonNode> unknownFields =
            new LinkedHashMap<>();

    @JsonSetter("birth")
    public void setBirth(JsonNode birth) {
        this.birthProvided = true;
        this.birth = birth;
    }

    @JsonSetter("isBirthdayPublic")
    public void setBirthdayPublic(JsonNode isBirthdayPublic) {
        this.birthdayPublicProvided = true;
        this.isBirthdayPublic = isBirthdayPublic;
    }

    @JsonAnySetter
    public void addUnknownField(
            String field,
            JsonNode value
    ) {
        unknownFields.put(field, value);
    }

    public boolean hasBirth() {
        return birthProvided;
    }

    public boolean hasBirthdayPublic() {
        return birthdayPublicProvided;
    }

    public JsonNode rawBirth() {
        return birth;
    }

    public JsonNode rawBirthdayPublic() {
        return isBirthdayPublic;
    }

    public Set<String> unknownFieldNames() {
        return Set.copyOf(unknownFields.keySet());
    }

    public LocalDate birthValue() {
        if (!birthProvided || birth == null || !birth.isTextual()) {
            throw new IllegalStateException(
                    "생년월일은 검증을 완료한 후 조회해야 합니다."
            );
        }

        return LocalDate.parse(
                birth.textValue(),
                BIRTH_FORMAT
        );
    }

    public boolean birthdayPublicValue() {
        if (!birthdayPublicProvided
                || isBirthdayPublic == null
                || !isBirthdayPublic.isBoolean()) {
            throw new IllegalStateException(
                    "생일 공개 여부는 검증을 완료한 후 조회해야 합니다."
            );
        }

        return isBirthdayPublic.booleanValue();
    }
}
