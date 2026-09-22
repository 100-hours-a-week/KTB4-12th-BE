package com.gift.gift.domain.preference.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.gift.gift.domain.preference.dto.request.SaveDislikeCategoriesRequest;
import com.gift.gift.domain.preference.dto.response.DislikeCategoryListResponse;
import com.gift.gift.domain.preference.dto.response.SaveDislikeCategoriesResponse;
import com.gift.gift.domain.preference.service.PreferenceQueryService;
import com.gift.gift.domain.preference.service.PreferenceSaveService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/preferences")
@RequiredArgsConstructor
public class PreferenceController {

    private final PreferenceQueryService preferenceQueryService;
    private final PreferenceSaveService preferenceSaveService;

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

    @PutMapping("/dislike-categories")
    public ResponseEntity<ApiResponse<SaveDislikeCategoriesResponse>>
    saveDislikeCategories(
            @CurrentUserId Long userId,
            @Valid @RequestBody SaveDislikeCategoriesRequest request
    ) {
        SaveDislikeCategoriesResponse response =
                preferenceSaveService.saveDislikeCategories(
                        userId,
                        request.categoryIds()
                );

        return ResponseEntity.ok(ApiResponse.success(
                "비선호 카테고리를 저장했습니다.",
                response
        ));
    }
}
