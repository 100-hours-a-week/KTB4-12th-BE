package com.gift.gift.domain.friend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.service.FriendService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/friends")
@RequiredArgsConstructor
public class FriendController {

    private final FriendService friendService;

    @GetMapping
    public ResponseEntity<ApiResponse<CursorPageResponse<FriendListItem>>> getFriends(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String cursor
    ) {
        CursorPageResponse<FriendListItem> response = friendService.getFriends(userId, cursor);

        return ResponseEntity.ok(ApiResponse.success("친구 목록을 조회했습니다.", response));
    }
}
