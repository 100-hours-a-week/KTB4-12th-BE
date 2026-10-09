package com.gift.gift.domain.friend.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.friend.dto.request.FriendRequestCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.service.FriendRequestService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/friend-requests")
@RequiredArgsConstructor
public class FriendRequestController {

    private final FriendRequestService requestService;

    @PostMapping
    public ResponseEntity<ApiResponse<FriendRequestResponse>> create(
            @CurrentUserId Long userId, @Valid @RequestBody FriendRequestCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("친구 요청을 보냈습니다.", requestService.create(userId, request)));
    }

    @GetMapping("/received")
    public ApiResponse<CursorPageResponse<FriendRequestResponse>> received(
            @CurrentUserId Long userId, @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success("받은 친구 요청을 조회했습니다.", requestService.getRequests(userId, true, cursor));
    }

    @GetMapping("/sent")
    public ApiResponse<CursorPageResponse<FriendRequestResponse>> sent(
            @CurrentUserId Long userId, @RequestParam(required = false) String cursor
    ) {
        return ApiResponse.success("보낸 친구 요청을 조회했습니다.", requestService.getRequests(userId, false, cursor));
    }

}
