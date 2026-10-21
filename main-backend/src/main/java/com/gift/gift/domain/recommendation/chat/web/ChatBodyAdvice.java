package com.gift.gift.domain.recommendation.chat.web;

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

import com.gift.gift.domain.recommendation.chat.controller.ChatController;
import com.gift.gift.domain.recommendation.chat.controller.ChatEventsController;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;

@ControllerAdvice(assignableTypes = {
        ChatController.class,
        ChatEventsController.class
})
public class ChatBodyAdvice extends RequestBodyAdviceAdapter {

    private static final int MAX_BYTES = 20_000;

    @Override
    public boolean supports(
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter
    ) {
        return parameter.getContainingClass() == ChatController.class
                || parameter.getContainingClass() == ChatEventsController.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage input,
            MethodParameter parameter,
            Type type,
            Class<? extends HttpMessageConverter<?>> converter
    ) throws IOException {
        if (input.getHeaders().getContentLength() > MAX_BYTES) {
            throw tooLarge();
        }

        byte[] bytes = input.getBody().readNBytes(MAX_BYTES + 1);

        if (bytes.length > MAX_BYTES) {
            throw tooLarge();
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

    private ChatException tooLarge() {
        return new ChatException(ChatErrorCode.PAYLOAD_TOO_LARGE);
    }
}
