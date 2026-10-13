package com.gift.gift.domain.recommendation.chat.config;

import java.time.*;
import java.util.Base64;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import com.gift.gift.domain.recommendation.chat.security.ChatConversationTokenProvider;
import com.gift.gift.global.config.JwtConfig;
import com.gift.gift.global.security.JwtProperties;

import static org.junit.jupiter.api.Assertions.*;

class ChatConversationConfigurationTest {

    @Test
    @DisplayName("대화 토큰 설정은 로그인용 JWT Bean을 추가하거나 교체하지 않는다")
    void context_keepsSingleLoginEncoderAndDecoder() {
        byte[] chatKey = new byte[32];
        chatKey[0] = 1;

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "app.security.jwt-com.gift.gift.global.security.JwtProperties",
                    JwtProperties.class,
                    () -> new JwtProperties(
                            Base64.getEncoder()
                                    .encodeToString(new byte[32]),
                            "https://login.example"
                    )
            );

            context.registerBean(
                    ChatConversationTokenProperties.class,
                    () -> new ChatConversationTokenProperties(
                            Base64.getEncoder()
                                    .encodeToString(chatKey),
                            "https://chat.example"
                    )
            );

            context.registerBean(
                    Clock.class,
                    Clock::systemUTC
            );

            context.register(
                    JwtConfig.class,
                    ChatConversationTokenProvider.class
            );

            context.refresh();

            assertEquals(
                    1,
                    context.getBeansOfType(JwtEncoder.class).size()
            );

            assertEquals(
                    1,
                    context.getBeansOfType(JwtDecoder.class).size()
            );

            assertNotNull(
                    context.getBean(ChatConversationTokenProvider.class)
            );
        }
    }
}
