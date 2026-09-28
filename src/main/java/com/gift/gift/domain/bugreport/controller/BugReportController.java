package com.gift.gift.domain.bugreport.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.service.BugReportService;

@RestController
@RequestMapping("/api/bug-report")
@RequiredArgsConstructor
public class BugReportController {

    private final BugReportService bugReportService;

    /*
     * 로그인 화면 등 인증 전 상태에서도 제보가 가능해야 해서
     * 이 API는 permitAll이다. 토큰이 있으면 사용자 ID를 함께 보내고,
     * 없으면(errorOnInvalidType=false) 익명으로 처리한다.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> submitBugReport(
            @AuthenticationPrincipal(errorOnInvalidType = false) Jwt jwt,
            HttpServletRequest request,
            @RequestPart(value = "payload_json", required = false)
            MultipartFile payloadJsonPart,
            @RequestPart(value = "files[0]", required = false)
            MultipartFile screenshot,
            @RequestPart(value = "files[1]", required = false)
            MultipartFile errorLog
    ) {
        bugReportService.submitBugReport(
                resolveUserId(jwt),
                request.getRemoteAddr(),
                readAsUtf8(payloadJsonPart),
                screenshot,
                errorLog
        );

        return ResponseEntity.noContent().build();
    }

    private Long resolveUserId(Jwt jwt) {
        return jwt != null ? Long.valueOf(jwt.getSubject()) : null;
    }

    /*
     * payload_json 파트는 전송 측이 charset을 명시하지 않을 수 있어
     * Content-Type 기반 자동 변환 대신 UTF-8로 직접 디코딩한다.
     */
    private String readAsUtf8(MultipartFile part) {
        if (part == null || part.isEmpty()) {
            return null;
        }

        try {
            return new String(part.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new BugReportException(
                    BugReportErrorCode.PAYLOAD_INVALID_FORMAT
            );
        }
    }
}
