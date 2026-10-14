package com.gift.gift.domain.recommendation.chat.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.service.ChatSseService;
import com.gift.gift.domain.recommendation.chat.support.ChatEventCursor;
import com.gift.gift.domain.recommendation.chat.web.ChatSseRelay;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/recommendations/conversations")
@RequiredArgsConstructor
public class ChatEventsController {

    private final ChatSseService service;
    private final ChatSseRelay relay;

    @GetMapping("/{conversationId}/turns/{turnId}/events")
    public void events(
            @CurrentUserId Long userId,
            @PathVariable String conversationId,
            @PathVariable String turnId,
            @RequestHeader(
                    value = "X-Conversation-Token",
                    required = false
            ) String token,
            @RequestBody(required = false) String body,
            @RequestParam MultiValueMap<String, String> query,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (body != null) {
            throw new ChatException(ChatErrorCode.INVALID_REQUEST);
        }

        long cursor = ChatEventCursor.parse(query);

        ChatSseService.Session session = service.open(
                userId,
                conversationId,
                token,
                turnId,
                cursor
        );

        relay.start(request, response, session);
    }
}
