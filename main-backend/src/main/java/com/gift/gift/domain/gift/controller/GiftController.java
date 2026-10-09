package com.gift.gift.domain.gift.controller;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.*;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.service.GiftQueryService;
import com.gift.gift.domain.gift.service.GiftService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/gifts")
@RequiredArgsConstructor
public class GiftController {

    private final GiftQueryService giftQueryService;
    private final GiftService giftService;

    @GetMapping("/sent")
    public ResponseEntity<ApiResponse<CursorPageResponse<SentGiftListItem>>> getSentGifts(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String cursor
    ) {
        CursorPageResponse<SentGiftListItem> response = giftQueryService.getSentGifts(userId, cursor);

        return ResponseEntity.ok(ApiResponse.success(
                listMessage(response, "보낸 선물 목록을 조회했습니다.", "보낸 선물 내역이 없습니다."),
                response
        ));
    }

    @GetMapping("/sent/{giftId}")
    public ResponseEntity<ApiResponse<GiftSentDetailResponse>> getSentGiftDetail(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId
    ) {
        GiftSentDetailResponse response = giftQueryService.getSentGiftDetail(userId, giftId);

        return ResponseEntity.ok(ApiResponse.success("보낸 선물 상세 정보를 조회했습니다.", response));
    }

    @GetMapping("/received")
    public ResponseEntity<ApiResponse<CursorPageResponse<ReceivedGiftListItem>>> getReceivedGifts(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String cursor
    ) {
        CursorPageResponse<ReceivedGiftListItem> response = giftQueryService.getReceivedGifts(userId, cursor);

        return ResponseEntity.ok(ApiResponse.success(
                listMessage(response, "받은 선물 목록을 조회했습니다.", "받은 선물 내역이 없습니다."),
                response
        ));
    }

    @GetMapping("/received/{giftId}")
    public ResponseEntity<ApiResponse<GiftReceivedDetailResponse>> getReceivedGiftDetail(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long giftId
    ) {
        GiftReceivedDetailResponse response = giftQueryService.getReceivedGiftDetail(userId, giftId);

        return ResponseEntity.ok(ApiResponse.success("받은 선물 상세 정보를 조회했습니다.", response));
    }

    @PostMapping("/preflight")
    public ResponseEntity<ApiResponse<GiftPreflightResponse>> preflight(
            @CurrentUserId Long senderId,
            @Valid @RequestBody GiftPreflightRequest request
    ) {
        GiftPreflightResponse response = giftService.preflight(senderId, request);

        return ResponseEntity.ok(ApiResponse.success("선물 사전 검증을 완료했습니다.", response));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GiftCreateResponse>> createGift(
            @CurrentUserId Long senderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @Valid @RequestBody GiftCreateRequest request
    ) {
        UUID idempotencyKey = parseIdempotencyKey(idempotencyKeyHeader);
        GiftCreateResponse response = giftService.createGiftResponse(senderId, idempotencyKey, request);

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("선물을 보냈습니다.", response));
    }

    private UUID parseIdempotencyKey(String idempotencyKeyHeader) {
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new GiftException(ErrorCode.INVALID_REQUEST);
        }

        try {
            return UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException exception) {
            throw new GiftException(ErrorCode.INVALID_REQUEST);
        }
    }

    private String listMessage(CursorPageResponse<?> response, String message, String emptyMessage) {
        return response.items().isEmpty() ? emptyMessage : message;
    }
}
