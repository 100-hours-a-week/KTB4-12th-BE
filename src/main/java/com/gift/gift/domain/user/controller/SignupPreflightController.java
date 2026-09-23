package com.gift.gift.domain.user.controller;

import java.util.concurrent.ConcurrentHashMap;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.user.dto.request.CheckEmailAvailabilityRequest;
import com.gift.gift.domain.user.dto.response.EmailAvailabilityResponse;
import com.gift.gift.domain.user.dto.response.SignupTermsResponse;
import com.gift.gift.domain.user.exception.UserErrorCode;
import com.gift.gift.domain.user.response.UserSuccessCode;
import com.gift.gift.domain.user.service.SignupPreflightService;
import com.gift.gift.global.exception.RequestValidationException;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class SignupPreflightController {

    private final SignupPreflightService signupPreflightService;

    @GetMapping("/terms")
    public ResponseEntity<ApiResponse<SignupTermsResponse>>
    getSignupTerms() {
        SignupTermsResponse response =
                signupPreflightService.getSignupTerms();
        UserSuccessCode successCode =
                UserSuccessCode.SIGNUP_TERMS_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }

    @PostMapping("/email-availability")
    public ResponseEntity<ApiResponse<EmailAvailabilityResponse>>
    checkEmailAvailability(
            @Valid @RequestBody CheckEmailAvailabilityRequest request,
            BindingResult bindingResult
    ) {

        if (bindingResult.hasErrors()) {
            throw new RequestValidationException(
                    UserErrorCode.INVALID_EMAIL_FORMAT.errorCode()
            );
        }

        EmailAvailabilityResponse response =
                signupPreflightService.checkEmailAvailability(
                        request.email()
                );

        UserSuccessCode successCode = response.available()
                ? UserSuccessCode.EMAIL_AVAILABLE
                : UserSuccessCode.EMAIL_UNAVAILABLE;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }
}
