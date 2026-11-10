package com.gift.gift.domain.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.auth.dto.request.EmailVerificationCodeRequest;
import com.gift.gift.domain.auth.dto.request.VerifyEmailCodeRequest;
import com.gift.gift.domain.auth.dto.response.EmailVerificationTokenResponse;
import com.gift.gift.domain.auth.response.AuthSuccessCode;
import com.gift.gift.domain.auth.service.EmailVerificationService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    @PostMapping("/email-verification-codes")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>>
    sendVerificationCode(
            @Valid @RequestBody EmailVerificationCodeRequest request,
            HttpServletRequest servletRequest
    ) {
        emailVerificationService.sendVerificationCode(
                request.email(),
                servletRequest.getRemoteAddr()
        );

        AuthSuccessCode successCode = AuthSuccessCode.EMAIL_VERIFICATION_CODE_SENT;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(successCode.message()));
    }

    @PostMapping("/email-verifications")
    public ResponseEntity<ApiResponse<EmailVerificationTokenResponse>>
    verifyCode(
            @Valid @RequestBody VerifyEmailCodeRequest request
    ) {
        String token = emailVerificationService.verifyCode(
                request.email(),
                request.code()
        );

        AuthSuccessCode successCode = AuthSuccessCode.EMAIL_VERIFICATION_COMPLETED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        new EmailVerificationTokenResponse(token)
                ));
    }
}
