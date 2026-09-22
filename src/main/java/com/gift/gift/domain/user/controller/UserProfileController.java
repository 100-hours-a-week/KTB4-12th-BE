package com.gift.gift.domain.user.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.request.CompleteOnboardingRequest;
import com.gift.gift.domain.user.dto.request.UpdateUserProfileRequest;
import com.gift.gift.domain.user.dto.response.CompleteOnboardingResponse;
import com.gift.gift.domain.user.dto.response.UpdateUserProfileResponse;
import com.gift.gift.domain.user.dto.response.UserProfileResponse;
import com.gift.gift.domain.user.service.OnboardingService;
import com.gift.gift.domain.user.service.UserProfileService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final OnboardingService onboardingService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserProfileResponse>>
    getMyProfile(
            @CurrentUserId Long userId
    ) {
        UserProfileResponse response =
                userProfileService.getMyProfile(userId);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "내 정보를 조회했습니다.",
                        response
                )
        );
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponse<UpdateUserProfileResponse>>
    updateMyProfile(
            @CurrentUserId Long userId,
            @Valid @RequestBody UpdateUserProfileRequest request
    ) {
        UpdateUserProfileResponse response =
                userProfileService.updateMyProfile(
                        userId,
                        request
                );

        return ResponseEntity.ok(
                ApiResponse.success(
                        "사용자 정보를 수정했습니다.",
                        response
                )
        );
    }

    @PatchMapping("/me/onboarding")
    public ResponseEntity<ApiResponse<CompleteOnboardingResponse>>
    completeOnboarding(
            @CurrentUserId Long userId,
            @Valid @RequestBody CompleteOnboardingRequest request
    ) {
        CompleteOnboardingResponse response =
                onboardingService.completeOnboarding(userId);

        return ResponseEntity.ok(
                ApiResponse.success(
                        "최초 로그인 설정을 완료했습니다.",
                        response
                )
        );
    }
}
