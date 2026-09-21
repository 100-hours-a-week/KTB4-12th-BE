package com.gift.gift.domain.preference.controller;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.preference.dto.response.DislikeCategoryListResponse;
import com.gift.gift.domain.preference.service.PreferenceQueryService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceQueryService preferenceQueryService;

    @GetMapping("/dislike-categories")
    public ResponseEntity<ApiResponse<DislikeCategoryListResponse>>
    getDislikeCategories(
            @CurrentUserId Long userId
    ) {
        DislikeCategoryListResponse response =
                preferenceQueryService.getDislikeCategories(userId);

        return ResponseEntity.ok(ApiResponse.success(
                "비선호 카테고리를 조회했습니다.",
                response
        ));
    }
}
