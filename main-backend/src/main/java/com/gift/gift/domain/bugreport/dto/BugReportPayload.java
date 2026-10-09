package com.gift.gift.domain.bugreport.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record BugReportPayload(List<Embed> embeds) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Embed(
            String description,
            String timestamp,
            List<Field> fields
    ) {

        public String fieldValue(String name) {
            if (fields == null) {
                return "";
            }

            return fields.stream()
                    .filter(field -> name.equals(field.name()))
                    .map(Field::value)
                    .findFirst()
                    .orElse("");
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Field(String name, String value) {
    }
}
