package com.gift.gift.domain.recommendation.controller;

import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.recommendation.dto.request.ProfileCallbackRequest;
import com.gift.gift.domain.recommendation.service.ProfileCallbackService;
import com.gift.gift.global.exception.ValidationErrorReason;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/api/internal/v1/recipients")
@RequiredArgsConstructor
public class InternalRecipientProfileController {

    private final ProfileCallbackService callbackService;

    @PostMapping("/{recipientUserId}/profile")
    public ResponseEntity<ApiResponse<Map<String, Object>>> saveProfile(
            @PathVariable("recipientUserId")
            @Positive(message = ValidationErrorReason.Message.OUT_OF_RANGE)
            Long recipientUserId,

            @Valid @RequestBody ProfileCallbackRequest request
    ) {
        callbackService.saveCallback(
                recipientUserId,
                request.recipientUserId(),
                request.sourceVersion(),
                request.recommendedProductIds()
        );

        return ResponseEntity.ok(
                ApiResponse.success("추천 결과를 저장했습니다.")
        );
    }
}
