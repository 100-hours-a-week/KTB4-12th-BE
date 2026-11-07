package com.gift.gift.domain.bugreport.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;
import com.gift.gift.domain.bugreport.service.BugReportService;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BugReportControllerTest {
    private static final String ID = "2d01816c-86e7-46a9-a138-51ce3ad21da8";
    private static final String BODY = "{\"reportId\":\"" + ID + "\",\"description\":\"버그 설명\"}";
    private BugReportService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(BugReportService.class);
        when(service.submitBugReport(any(), anyString(), any())).thenReturn(ID);
        mvc = MockMvcBuilders.standaloneSetup(new BugReportController(service))
            .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
            .setControllerAdvice(new GlobalExceptionHandler(), new BugReportRequestBodyAdvice()).build();
    }

    @Test
    @DisplayName("익명 JSON 접수 후 202와 reportId를 반환한다")
    void acceptsAnonymousJson() throws Exception {
        mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.data.reportId").value(ID));
        verify(service).submitBugReport(isNull(), anyString(), any());
    }

    @Test
    @DisplayName("누락된 ID와 축약 UUID 및 빈 설명은 필드 검증에서 거부한다")
    void rejectsInvalidFields() throws Exception {
        for (String body : new String[] {"{}", BODY.replace(ID, "1-1-1-1-1"),
            BODY.replace("버그 설명", " "), BODY.replace(ID, "not-uuid")}) {
            mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.details").isNotEmpty());
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("잘못된 JSON과 날짜는 400이며 서비스로 전달하지 않는다")
    void rejectsMalformedJsonAndTimestamp() throws Exception {
        for (String body : new String[] {"{", BODY.replace("}", ",\"reportedAt\":\"yesterday\"}")}) {
            mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("UTF-8 본문은 정확히 10KB까지 허용하고 초과는 거부한다")
    void enforcesUtf8ByteLimit() throws Exception {
        int padding = 10240 - BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String atLimit = BODY + " ".repeat(padding);
        mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(atLimit))
            .andExpect(status().isAccepted());
        clearInvocations(service);
        mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(atLimit + " "))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("DB 접수 실패는 503이며 성공 reportId를 반환하지 않는다")
    void returns503ForFailedAcceptance() throws Exception {
        when(service.submitBugReport(any(), anyString(), any()))
            .thenThrow(new BugReportException(BugReportErrorCode.ACCEPTANCE_FAILED));
        mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("기존 rate limit은 429로 반환한다")
    void preservesRateLimitResponse() throws Exception {
        when(service.submitBugReport(any(), anyString(), any()))
            .thenThrow(new BugReportException(BugReportErrorCode.RATE_LIMIT_EXCEEDED));
        mvc.perform(post("/bug-report").contentType(MediaType.APPLICATION_JSON).content(BODY))
            .andExpect(status().isTooManyRequests());
    }
}
