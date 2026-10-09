package com.gift.gift.domain.preference.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;

import com.gift.gift.domain.preference.validation.ValidGiftPreference;

@ValidGiftPreference
public class SaveGiftPreferenceRequest {

    private boolean preferenceProvided;
    private String preference;

    @JsonSetter("preference")
    public void setPreference(String preference) {
        this.preferenceProvided = true;
        this.preference = preference;
    }

    public boolean hasPreference() {
        return preferenceProvided;
    }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object value) {
        throw new IllegalArgumentException("허용되지 않은 요청 필드입니다.");
    }

    public String preferenceValue() {
        if (!preferenceProvided) {
            throw new IllegalStateException("취향은 검증을 완료한 후 조회해야 합니다.");
        }
        return preference;
    }
}
