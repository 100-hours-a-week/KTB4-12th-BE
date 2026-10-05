package com.gift.gift.domain.recommendation.dto.request;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSetter;
import tools.jackson.databind.JsonNode;

import com.gift.gift.domain.recommendation.validation.ValidProfileCallbackRequest;

@ValidProfileCallbackRequest
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProfileCallbackRequest {

    private JsonNode recipientUserId;
    private JsonNode sourceVersion;
    private JsonNode profileStatus;
    private JsonNode recommendedProductIds;

    @JsonSetter("recipientUserId")
    public void setRecipientUserId(JsonNode recipientUserId) {
        this.recipientUserId = recipientUserId;
    }

    @JsonSetter("sourceVersion")
    public void setSourceVersion(JsonNode sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    @JsonSetter("profileStatus")
    public void setProfileStatus(JsonNode profileStatus) {
        this.profileStatus = profileStatus;
    }

    @JsonSetter("recommendedProductIds")
    public void setRecommendedProductIds(JsonNode recommendedProductIds) {
        this.recommendedProductIds = recommendedProductIds;
    }

    public JsonNode rawRecipientUserId() {
        return recipientUserId;
    }

    public JsonNode rawSourceVersion() {
        return sourceVersion;
    }

    public JsonNode rawProfileStatus() {
        return profileStatus;
    }

    public JsonNode rawRecommendedProductIds() {
        return recommendedProductIds;
    }

    // 아래 값 변환 메서드는 요청 검증을 통과한 후 사용한다.
    public Long recipientUserId() {
        return recipientUserId.longValue();
    }

    public long sourceVersion() {
        return sourceVersion.longValue();
    }

    public String profileStatus() {
        return profileStatus.stringValue();
    }

    public List<Long> recommendedProductIds() {
        List<Long> productIds = new ArrayList<>();

        for (JsonNode productId : recommendedProductIds) {
            productIds.add(productId.longValue());
        }

        return List.copyOf(productIds);
    }
}
