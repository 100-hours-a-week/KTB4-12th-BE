package com.gift.gift.domain.user.dto.request;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import tools.jackson.databind.JsonNode;

import com.gift.gift.domain.user.validation.ValidOnboardingCompletion;

@ValidOnboardingCompletion
public class CompleteOnboardingRequest {

    private JsonNode completed;
    private boolean completedProvided;

    private final Map<String, JsonNode> unknownFields =
            new LinkedHashMap<>();

    @JsonSetter("completed")
    public void setCompleted(JsonNode completed) {
        this.completedProvided = true;
        this.completed = completed;
    }

    @JsonAnySetter
    public void addUnknownField(
            String field,
            JsonNode value
    ) {
        unknownFields.put(field, value);
    }

    public boolean hasCompleted() {
        return completedProvided;
    }

    public JsonNode rawCompleted() {
        return completed;
    }

    public Set<String> unknownFieldNames() {
        return Set.copyOf(unknownFields.keySet());
    }
}
