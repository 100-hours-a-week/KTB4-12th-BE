package com.gift.gift.domain.bugreport.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BugReportPayload(List<Embed> embeds) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embed(String description) {
    }
}
