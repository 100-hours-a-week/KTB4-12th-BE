package com.gift.gift.domain.preference.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record GiftPreferenceResponse(String preference) {

    public static GiftPreferenceResponse from(String preference) {
        return new GiftPreferenceResponse(preference);
    }
}
