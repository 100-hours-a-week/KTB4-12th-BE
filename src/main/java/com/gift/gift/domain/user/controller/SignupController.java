package com.gift.gift.domain.user.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.request.SignupRequest;
import com.gift.gift.domain.user.dto.response.SignupResponse;
import com.gift.gift.domain.user.service.SignupService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class SignupController {

    private final SignupService signupService;

    @PostMapping("/signup")
    public ResponseEntity<ApiResponse<SignupResponse>> signup(
            @Valid @RequestBody SignupRequest request
    ) {
        SignupResponse response = signupService.signup(request);
        UserSuccessCode successCode =
                UserSuccessCode.SIGNUP_COMPLETED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }
}
