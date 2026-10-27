package com.gift.gift.domain.randombox.controller;

import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.randombox.dto.response.RandomBoxResponse;
import com.gift.gift.domain.randombox.service.RandomBoxService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/random-boxes")
@RequiredArgsConstructor
public class RandomBoxController {
    private final RandomBoxService service;

    @GetMapping("/{boxId}")
    public ResponseEntity<ApiResponse<RandomBoxResponse>> detail(@CurrentUserId Long userId,
            @PathVariable @Positive Long boxId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("랜덤박스를 조회했습니다.", service.detail(userId, boxId)));
    }

    @PostMapping("/{boxId}/open")
    public ResponseEntity<ApiResponse<RandomBoxResponse>> open(@CurrentUserId Long userId,
            @PathVariable @Positive Long boxId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success("랜덤박스 개봉 결과를 확인했습니다.", service.open(userId, boxId)));
    }
}
