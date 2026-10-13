package com.gift.gift.domain.credit.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.credit.dto.response.CreditBalanceResponse;
import com.gift.gift.domain.credit.response.CreditSuccessCode;
import com.gift.gift.domain.credit.service.CreditQueryService;
import com.gift.gift.global.response.ApiResponse;
import com.gift.gift.global.security.CurrentUserId;

@RestController
@RequestMapping("/credits")
@RequiredArgsConstructor
public class CreditController {

    private final CreditQueryService creditQueryService;

    @GetMapping("/balance")
    public ResponseEntity<ApiResponse<CreditBalanceResponse>>
    getBalance(@CurrentUserId Long userId) {

        CreditBalanceResponse response = creditQueryService.getBalance(userId);

        CreditSuccessCode successCode = CreditSuccessCode.BALANCE_RETRIEVED;

        return ResponseEntity
                .status(successCode.status())
                .body(ApiResponse.success(
                        successCode.message(),
                        response
                ));
    }
}
