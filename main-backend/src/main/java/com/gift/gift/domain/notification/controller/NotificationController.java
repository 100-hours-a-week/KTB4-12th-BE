package com.gift.gift.domain.notification.controller;

import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.notification.dto.response.NotificationListItem;
import com.gift.gift.domain.notification.dto.response.NotificationReadResponse;
import com.gift.gift.domain.notification.dto.response.NotificationUnreadCountResponse;
import com.gift.gift.domain.notification.service.NotificationService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<NotificationUnreadCountResponse>> getUnreadCount(
            @CurrentUserId Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "읽지 않은 알림 개수를 조회했습니다.",
                notificationService.getUnreadCount(userId)
        ));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CursorPageResponse<NotificationListItem>>> getNotifications(
            @CurrentUserId Long userId,
            @RequestParam(required = false) String cursor
    ) {
        CursorPageResponse<NotificationListItem> response =
                notificationService.getNotifications(userId, cursor);

        String message = response.items().isEmpty()
                ? "알림 내역이 없습니다."
                : "알림 목록을 조회했습니다.";

        return ResponseEntity.ok(ApiResponse.success(message, response));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationReadResponse>> markAsRead(
            @CurrentUserId Long userId,
            @PathVariable @Positive Long notificationId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                "알림을 읽음 처리했습니다.",
                notificationService.markAsRead(userId, notificationId)
        ));
    }

}
