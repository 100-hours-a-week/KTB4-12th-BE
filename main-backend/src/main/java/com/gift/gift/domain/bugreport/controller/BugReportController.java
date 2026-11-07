package com.gift.gift.domain.bugreport.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.gift.gift.domain.bugreport.dto.BugReportAcceptedResponse;
import com.gift.gift.domain.bugreport.dto.CreateBugReportRequest;
import com.gift.gift.domain.bugreport.service.BugReportService;
import com.gift.gift.global.response.ApiResponse;

@RestController
@RequestMapping("/bug-report")
@RequiredArgsConstructor
public class BugReportController {
    private final BugReportService bugReportService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<BugReportAcceptedResponse>> submitBugReport(
            @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt,
            HttpServletRequest request,
            @Valid @RequestBody CreateBugReportRequest payload
    ) {
        String reportId = bugReportService.submitBugReport(
                jwt == null ? null : Long.valueOf(jwt.getSubject()), request.getRemoteAddr(), payload);
        return ResponseEntity.accepted().body(ApiResponse.success(
                "버그 제보를 접수했습니다.", BugReportAcceptedResponse.from(reportId)));
    }
}
