package com.gift.gift.domain.user.controller;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.request.CheckEmailAvailabilityRequest;
import com.gift.gift.domain.user.dto.response.EmailAvailabilityResponse;
import com.gift.gift.domain.user.dto.response.SignupTermsResponse;
import com.gift.gift.domain.user.service.SignupPreflightService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.RequestValidationException;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class SignupPreflightController {

    private final SignupPreflightService signupPreflightService;

    @GetMapping("/terms")
    public ApiResponse<SignupTermsResponse> getSignupTerms() {
        SignupTermsResponse response =
                signupPreflightService.getSignupTerms();

        return ApiResponse.success(
                "회원가입 약관을 조회했습니다.",
                response
        );
    }

    @PostMapping("/email-availability")
    public ApiResponse<EmailAvailabilityResponse> checkEmailAvailability(
            @Valid @RequestBody CheckEmailAvailabilityRequest request,
            BindingResult bindingResult
    ) {
        if (bindingResult.hasErrors()) {
            throw new RequestValidationException(
                    ErrorCode.INVALID_EMAIL_FORMAT
            );
        }

        EmailAvailabilityResponse response =
                signupPreflightService.checkEmailAvailability(
                        request.email()
                );

        String message = response.available()
                ? "사용할 수 있는 이메일입니다."
                : "이미 사용 중인 이메일입니다.";

        return ApiResponse.success(message, response);
    }
}
