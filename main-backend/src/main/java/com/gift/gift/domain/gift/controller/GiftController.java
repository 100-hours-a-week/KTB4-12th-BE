package com.gift.gift.domain.gift.controller;

import java.time.Clock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.*;
import com.gift.gift.domain.gift.response.GiftSuccessCode;
import com.gift.gift.domain.gift.service.GiftQueryService;
import com.gift.gift.domain.gift.service.GiftService;
import com.gift.gift.domain.gift.service.GiftAcceptanceResult;
import com.gift.gift.domain.gift.service.GiftAcceptanceService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/gifts")
@RequiredArgsConstructor
public class GiftController {

    private final GiftQueryService giftQueryService;
    private final GiftService giftService;
    private final GiftAcceptanceService giftAcceptanceService;
    private final Clock clock;

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

    @PostMapping("/received/{giftId}/accept")
    public ResponseEntity<ApiResponse<GiftAcceptanceResponse>> accept(
            @CurrentUserId Long recipientId,
            @PathVariable @Positive Long giftId
    ) {
        GiftAcceptanceResult result = giftAcceptanceService.accept(recipientId, giftId);

        GiftAcceptanceResponse response = GiftAcceptanceResponse.from(result, clock.getZone());

        GiftSuccessCode successCode = GiftSuccessCode.GIFT_ACCEPTED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(successCode.message(), response));
    }

    @PostMapping("/preflight")
    public ResponseEntity<ApiResponse<GiftPreflightResponse>> preflight(
            @CurrentUserId Long senderId,
            @Valid @RequestBody GiftPreflightRequest request
    ) {
        GiftPreflightResponse response = giftService.preflight(senderId, request);

        return ResponseEntity.ok(ApiResponse.success("선물 사전 검증을 완료했습니다.", response));
    }

    private String listMessage(CursorPageResponse<?> response, String message, String emptyMessage) {
        return response.items().isEmpty() ? emptyMessage : message;
    }
}
