package com.gift.gift.domain.bugreport.controller;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import com.gift.gift.domain.bugreport.dto.CreateBugReportRequest;
import com.gift.gift.domain.bugreport.exception.BugReportErrorCode;
import com.gift.gift.domain.bugreport.exception.BugReportException;

@ControllerAdvice(assignableTypes = BugReportController.class)
public class BugReportRequestBodyAdvice extends RequestBodyAdviceAdapter {
    private static final int MAX_REQUEST_BYTES = 10 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
        Class<? extends HttpMessageConverter<?>> converterType) {
        return parameter.getParameterType() == CreateBugReportRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage input, MethodParameter parameter,
        Type targetType, Class<? extends HttpMessageConverter<?>> converterType) throws IOException {
        // Content-Length가 없는 chunked 요청도 역직렬화 전에 같은 제한을 적용한다.
        byte[] bytes = input.getBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (bytes.length > MAX_REQUEST_BYTES) {
            throw new BugReportException(BugReportErrorCode.PAYLOAD_TOO_LARGE);
        }
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }
            @Override
            public HttpHeaders getHeaders() {
                return input.getHeaders();
            }
        };
    }
}
