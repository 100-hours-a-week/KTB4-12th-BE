package com.gift.gift.domain.recommendation.chat.controller;

import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.recommendation.chat.dto.request.ChatRequests;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.Conversation;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.CreatedConversation;
import com.gift.gift.domain.recommendation.chat.dto.response.ChatModels.Turn;
import com.gift.gift.domain.recommendation.chat.service.ChatService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/recommendations/conversations")
@RequiredArgsConstructor
public class ChatController {
    private final ChatService service;

    @PutMapping("/{conversationId}")
    public ResponseEntity<ApiResponse<CreatedConversation>> create(
            @CurrentUserId Long userId,
            @AuthenticationPrincipal Jwt loginJwt,
            @PathVariable String conversationId,
            @RequestBody(required = false) ChatRequests.Create body,
            @RequestParam Map<String, String> query
    ) {
        noExtras(query, null);

        var result = service.create(
                userId,
                conversationId,
                body,
                loginJwt == null ? null : loginJwt.getExpiresAt()
        );

        return ResponseEntity.status(result.status()).body(
                ApiResponse.success(
                        result.status() == 201
                                ? "대화를 생성했습니다."
                                : "대화를 복구했습니다.",
                        result.data()
                )
        );
    }

    @GetMapping("/{conversationId}")
    public ApiResponse<Conversation> conversation(
            @CurrentUserId Long userId,
            @PathVariable String conversationId,
            @RequestHeader(
                    value = "X-Conversation-Token",
                    required = false
            ) String token,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> query
    ) {
        noExtras(query, body);

        return ApiResponse.success(
                "대화를 조회했습니다.",
                service.conversation(userId, conversationId, token)
        );
    }

    @PutMapping("/{conversationId}/turns/{turnId}")
    public ResponseEntity<ApiResponse<Turn>> submit(
            @CurrentUserId Long userId,
            @PathVariable String conversationId,
            @PathVariable String turnId,
            @RequestHeader(
                    value = "X-Conversation-Token",
                    required = false
            ) String token,
            @RequestBody(required = false) ChatRequests.Submit body,
            @RequestParam Map<String, String> query
    ) {
        noExtras(query, null);

        var result = service.submit(
                userId,
                conversationId,
                token,
                turnId,
                body
        );

        return ResponseEntity.status(result.status()).body(
                ApiResponse.success(
                        result.status() == 202
                                ? "질문을 접수했습니다."
                                : "질문 상태를 조회했습니다.",
                        result.data()
                )
        );
    }

    @GetMapping("/{conversationId}/turns/{turnId}")
    public ApiResponse<Turn> turn(
            @CurrentUserId Long userId,
            @PathVariable String conversationId,
            @PathVariable String turnId,
            @RequestHeader(
                    value = "X-Conversation-Token",
                    required = false
            ) String token,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> query
    ) {
        noExtras(query, body);

        return ApiResponse.success(
                "질문 상태를 조회했습니다.",
                service.turn(userId, conversationId, token, turnId)
        );
    }

    @PutMapping("/{conversationId}/turns/{turnId}/cancellation")
    public ApiResponse<Turn> cancel(
            @CurrentUserId Long userId,
            @PathVariable String conversationId,
            @PathVariable String turnId,
            @RequestHeader(
                    value = "X-Conversation-Token",
                    required = false
            ) String token,
            @RequestBody(required = false) String body,
            @RequestParam Map<String, String> query
    ) {
        noExtras(query, body);

        return ApiResponse.success(
                "질문 상태를 조회했습니다.",
                service.cancel(userId, conversationId, token, turnId)
        );
    }

    private void noExtras(Map<String, String> query, String body) {
        if (!query.isEmpty() || body != null) {
            throw ChatRequests.invalid();
        }
    }
}
