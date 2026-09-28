package com.gift.gift.infrastructure.discord;

import org.springframework.http.MediaType;

public record DiscordAttachment(
        String partName,
        String filename,
        MediaType contentType,
        byte[] content
) {
}
