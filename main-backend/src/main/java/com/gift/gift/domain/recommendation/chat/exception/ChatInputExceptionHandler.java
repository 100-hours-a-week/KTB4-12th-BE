package com.gift.gift.domain.recommendation.chat.exception;

import java.util.List;
import java.util.UUID;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.gift.gift.domain.recommendation.chat.controller.ChatController;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.ValidationDetail;
import com.gift.gift.global.response.ApiResponse;

@Slf4j
@RestControllerAdvice(assignableTypes = ChatController.class)
public class ChatInputExceptionHandler {
    @ExceptionHandler(ChatInputException.class)
    public ResponseEntity<ApiResponse<Void>> invalidMessage(
            ChatInputException exception
    ) {
        String traceId = MDC.get("traceId");

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        log.warn(
                "Chat input rejected: traceId={}, reason={}",
                traceId,
                exception.reason()
        );

        return ResponseEntity.badRequest().body(
                ApiResponse.error(
                        ErrorCode.INVALID_REQUEST,
                        "입력값을 확인해 주세요.",
                        traceId,
                        List.of(new ValidationDetail("message", exception.reason()))
                )
        );
    }
}
