package com.gift.gift.domain.recommendation.chat.controller;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import com.gift.gift.domain.recommendation.chat.config.ChatConversationTokenProperties;
import com.gift.gift.domain.recommendation.chat.exception.ChatErrorCode;
import com.gift.gift.domain.recommendation.chat.exception.ChatException;
import com.gift.gift.domain.recommendation.chat.exception.ChatInputExceptionHandler;
import com.gift.gift.domain.recommendation.chat.model.ChatConversationIdentity;
import com.gift.gift.domain.recommendation.chat.security.ChatConversationTokenProvider;
import com.gift.gift.domain.recommendation.chat.service.ChatConversationAccessService;
import com.gift.gift.domain.recommendation.chat.service.ChatDailyUsageService;
import com.gift.gift.domain.recommendation.chat.service.ChatService;
import com.gift.gift.domain.recommendation.chat.web.ChatBodyAdvice;
import com.gift.gift.domain.user.service.AuthenticatedUserService;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.security.JwtProperties;
import com.gift.gift.infrastructure.ai.chat.AiChatClient;
import com.gift.gift.infrastructure.ai.chat.AiChatClientConfig;
import com.gift.gift.infrastructure.ai.chat.AiChatProperties;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatJsonApiTest {
    private static final String BASE = "http://ai.test/v1/chat/conversations";
    private static final String ID = "22222222-2222-4222-8222-222222222222";
    private static final String TURN = "11111111-1111-4111-8111-111111111111";
    private static final String PUBLIC = "/recommendations/conversations/" + ID;
    private static final String QUESTION = "선물 추천";
    private static final Instant NOW = Instant.parse("2026-10-11T00:00:00Z");

    private MockRestServiceServer server;
    private MockMvc mvc;
    private ChatConversationTokenProvider tokens;
    private ChatDailyUsageService dailyUsageService;
    private String conversationToken;

    @BeforeEach
    void setUp() {
        AiChatProperties props = new AiChatProperties(
                URI.create(BASE),
                "stub-service-token",
                Duration.ofSeconds(1),
                Duration.ofSeconds(2)
        );

        RestClient.Builder builder = new AiChatClientConfig()
                .aiChatRestClient(props)
                .mutate();

        server = MockRestServiceServer.bindTo(builder).build();

        AiChatClient client = new AiChatClient(
                builder.build(),
                JsonMapper.builder().build()
        );

        tokens = spy(new ChatConversationTokenProvider(
                new ChatConversationTokenProperties(
                        key(1),
                        "https://chat.test"
                ),
                new JwtProperties(key(2), "https://login.test"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        ));

        AuthenticatedUserService users = mock(AuthenticatedUserService.class);
        when(users.isActive(anyLong())).thenReturn(true);

        ChatConversationAccessService access = new ChatConversationAccessService(users, tokens);
        dailyUsageService = mock(
                ChatDailyUsageService.class
        );
        ChatService service = new ChatService(
                access,
                tokens,
                client,
                dailyUsageService
        );

        mvc = MockMvcBuilders.standaloneSetup(new ChatController(service))
                .setCustomArgumentResolvers(
                        new AuthenticationPrincipalArgumentResolver()
                )
                .setControllerAdvice(
                        new ChatInputExceptionHandler(),
                        new ChatBodyAdvice(),
                        new GlobalExceptionHandler()
                )
                .build();

        login(7L);

        conversationToken = tokens.issue(
                new ChatConversationIdentity(UUID.fromString(ID), 7L, 42L),
                NOW.plusSeconds(3600)
        ).value();

        clearInvocations(tokens);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        server.verify();
    }

    @Test
    @DisplayName("대화 201 생성과 200 복구 후에만 로그인 만료 시각의 토큰을 발급한다")
    void createAndRecover() throws Exception {
        for (int code : new int[]{201, 200}) {
            nextServer().expect(requestTo(BASE + "/" + ID))
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer stub-service-token"
                    ))
                    .andExpect(content().json(
                            "{\"senderUserId\":7,\"recipientUserId\":42}"
                    ))
                    .andRespond(
                            withStatus(HttpStatusCode.valueOf(code))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(conversation())
                    );

            mvc.perform(
                            put(PUBLIC)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"recipientUserId\":42}")
                    )
                    .andExpect(status().is(code))
                    .andExpect(jsonPath("$.data.conversationToken").isNotEmpty());
        }

        verify(tokens, times(2)).issue(any(), eq(NOW.plusSeconds(3600)));
    }

    @Test
    @DisplayName("AI 오류는 공개 코드로 변환하고 생성 실패 시 토큰을 발급하지 않는다")
    void upstreamErrors() throws Exception {
        int[] statuses = {400, 401, 403, 404, 409, 413, 429, 500, 503};

        String[] codes = {
                "INVALID_REQUEST",
                "UNAUTHORIZED",
                "UNAUTHORIZED",
                "NOT_FOUND",
                "CONFLICT",
                "PAYLOAD_TOO_LARGE",
                "TOO_MANY_REQUESTS",
                "INTERNAL_SERVER_ERROR",
                "SERVICE_UNAVAILABLE"
        };

        for (int i = 0; i < statuses.length; i++) {
            nextServer().expect(requestTo(BASE + "/" + ID))
                    .andRespond(
                            withStatus(HttpStatusCode.valueOf(statuses[i]))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(error(codes[i]))
                    );

            int expected = switch (statuses[i]) {
                case 401, 403, 500, 503 -> 503;
                default -> statuses[i];
            };

            mvc.perform(
                            put(PUBLIC)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"recipientUserId\":42}")
                    )
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.error.code").value(
                            expected == 503 ? "SERVICE_UNAVAILABLE" : codes[i]
                    ))
                    .andExpect(jsonPath("$.message").value(switch (expected) {
                        case 400 -> "요청 형식이나 값이 올바르지 않습니다.";
                        case 404 -> "대화 또는 질문을 찾을 수 없습니다.";
                        case 409 -> "같은 대화 ID에 다른 사용자 정보를 사용할 수 없습니다.";
                        case 413 -> "요청 본문 크기가 너무 큽니다.";
                        case 429 -> "요청이 너무 많아요. 잠시 후 다시 시도해주세요.";
                        default -> "AI 서비스에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.";
                    }))
                    .andExpect(jsonPath("$.error.traceId").isNotEmpty())
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        verify(tokens, never()).issue(any(), any());
    }

    @Test
    @DisplayName("질문 충돌은 공개 CONFLICT 코드와 질문 전용 메시지를 반환한다")
    void turnConflict_keepsPublicCodeAndChatMessage() throws Exception {
        server.expect(requestTo(BASE + "/" + ID + "/turns/" + TURN))
                .andRespond(withStatus(HttpStatus.CONFLICT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(error("CONFLICT")));

        mvc.perform(put(PUBLIC + "/turns/" + TURN)
                        .header("X-Conversation-Token", conversationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"선물 추천\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.message").value(
                        "질문 ID 또는 현재 대화 처리 상태가 요청과 충돌합니다."
                ));
    }

    @Test
    @DisplayName("일일 질문 한도를 모두 사용하면 AI 호출 없이 429를 반환한다")
    void submitTurn_rejectsDailyLimitBeforeAiCall() throws Exception {
        doThrow(new ChatException(ChatErrorCode.DAILY_LIMIT_EXCEEDED))
                .when(dailyUsageService)
                .consume(anyLong(), any(), any());

        mvc.perform(put(PUBLIC + "/turns/" + TURN)
                        .header("X-Conversation-Token", conversationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"선물 추천\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value(
                        "AI_DAILY_LIMIT_EXCEEDED"
                ))
                .andExpect(jsonPath("$.message").value(
                        "오늘 사용할 수 있는 AI 채팅 횟수를 모두 사용했습니다."
                ));
    }

    @Test
    @DisplayName("대화 토큰 누락·변조는 동일한 채팅 404 메시지로 AI 호출 전에 차단된다")
    void inaccessibleConversation_usesUniformNotFound() throws Exception {
        for (String token : new String[]{"", "invalid-token"}) {
            mvc.perform(get(PUBLIC).header("X-Conversation-Token", token))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                    .andExpect(jsonPath("$.message").value("대화 또는 질문을 찾을 수 없습니다."));
        }
    }

    @Test
    @DisplayName("timeout·HTML·손상된 JSON·응답 ID 불일치는 503이며 토큰을 발급하지 않는다")
    void unavailableResponses() throws Exception {
        nextServer().expect(requestTo(BASE + "/" + ID))
                .andRespond(withException(new SocketTimeoutException()));

        mvc.perform(
                        put(PUBLIC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"recipientUserId\":42}")
                )
                .andExpect(status().isServiceUnavailable());

        for (String body : new String[]{
                "<html>gateway</html>",
                "{broken",
                conversation().replace(ID, TURN)
        }) {
            nextServer().expect(requestTo(BASE + "/" + ID))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            mvc.perform(
                            put(PUBLIC)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"recipientUserId\":42}")
                    )
                    .andExpect(status().isServiceUnavailable());
        }

        nextServer().expect(requestTo(BASE + "/" + ID))
                .andRespond(
                        withStatus(HttpStatus.BAD_GATEWAY)
                                .contentType(MediaType.TEXT_HTML)
                                .body("<html>gateway</html>")
                );

        mvc.perform(
                        put(PUBLIC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"recipientUserId\":42}")
                )
                .andExpect(status().isServiceUnavailable());

        verify(tokens, never()).issue(any(), any());
    }

    @Test
    @DisplayName("질문 202 접수와 종료된 동일 턴의 200 복구를 구분하고 부분 답변을 보존한다")
    void submitAndRestore() throws Exception {
        for (String state : new String[]{
                "PROCESSING",
                "COMPLETED",
                "FAILED",
                "CANCELLED"
        }) {
            int code = state.equals("PROCESSING") ? 202 : 200;

            nextServer().expect(
                            requestTo(BASE + "/" + ID + "/turns/" + TURN)
                    )
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(content().json("{\"message\":\"선물 추천\"}"))
                    .andRespond(
                            withStatus(HttpStatusCode.valueOf(code))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(turn(state, QUESTION))
                    );

            mvc.perform(
                            put(PUBLIC + "/turns/" + TURN)
                                    .header("X-Conversation-Token", conversationToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"message\":\"  선물 추천  \"}")
                    )
                    .andExpect(status().is(code))
                    .andExpect(jsonPath("$.data.status").value(state))
                    .andExpect(jsonPath("$.data.blocks[0].text").value("부분 답변"));
        }
    }

    @Test
    @DisplayName("GET 대화·턴으로 접수 결과를 복구하고 취소는 Body 없이 종료 상태를 반환한다")
    void queryAndCancel() throws Exception {
        nextServer().expect(requestTo(BASE + "/" + ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(conversation(), MediaType.APPLICATION_JSON));

        mvc.perform(
                        get(PUBLIC)
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isOk());

        nextServer().expect(requestTo(BASE + "/" + ID + "/turns/" + TURN))
                .andExpect(method(HttpMethod.GET))
                .andRespond(
                        withSuccess(
                                turn("PROCESSING", QUESTION),
                                MediaType.APPLICATION_JSON
                        )
                );

        mvc.perform(
                        get(PUBLIC + "/turns/" + TURN)
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isOk());

        for (int i = 0; i < 2; i++) {
            nextServer().expect(
                            requestTo(
                                    BASE + "/" + ID + "/turns/" + TURN + "/cancellation"
                            )
                    )
                    .andExpect(method(HttpMethod.PUT))
                    .andExpect(content().string(""))
                    .andRespond(
                            withSuccess(
                                    turn("COMPLETED", QUESTION),
                                    MediaType.APPLICATION_JSON
                            )
                    );

            mvc.perform(
                            put(PUBLIC + "/turns/" + TURN + "/cancellation")
                                    .header("X-Conversation-Token", conversationToken)
                    )
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        }
    }

    @Test
    @DisplayName("누락·공백·타입·길이·추가 필드·Query·취소 Body는 AI 호출 전에 거절한다")
    void invalidRequests() throws Exception {
        for (String body : new String[]{
                "{}",
                "{\"message\":null}",
                "{\"message\":\"  \"}",
                "{\"message\":1}",
                "{\"message\":true}",
                "{\"message\":\"" + "가".repeat(101) + "\"}",
                "{\"message\":\"" + "😀".repeat(101) + "\"}",
                "{\"message\":\"ok\",\"extra\":1}"
        }) {
            mvc.perform(
                            put(PUBLIC + "/turns/" + TURN)
                                    .header("X-Conversation-Token", conversationToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                    )
                    .andExpect(status().isBadRequest());
        }

        for (String recipient : new String[]{
                "\"42\"",
                "true",
                "42.5",
                "null",
                "0",
                "9007199254740992"
        }) {
            mvc.perform(
                            put(PUBLIC)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"recipientUserId\":" + recipient + "}")
                    )
                    .andExpect(status().isBadRequest());
        }

        mvc.perform(
                        put(PUBLIC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"recipientUserId\":42}" + " ".repeat(20_000))
                )
                .andExpect(status().is(413));

        mvc.perform(
                        put(PUBLIC)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"recipientUserId\":42,\"senderUserId\":8}")
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        put(PUBLIC + "/turns/" + TURN)
                                .header("X-Conversation-Token", conversationToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"message\":\"" + "가".repeat(101) + "\"}")
                )
                .andExpect(jsonPath("$.error.details[0].reason").value("TOO_LONG"));

        mvc.perform(
                        get(PUBLIC + "?extra=1")
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        put(PUBLIC + "/turns/" + TURN + "/cancellation")
                                .header("X-Conversation-Token", conversationToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(status().isBadRequest());

        mvc.perform(
                        put(PUBLIC + "/turns/" + TURN)
                                .header("X-Conversation-Token", conversationToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}")
                )
                .andExpect(jsonPath("$.error.details[0].reason").value("REQUIRED"));
    }

    @Test
    @DisplayName("한글·이모지 100 코드 포인트는 허용하고 숫자 문자열 입력은 거절한다")
    void unicodeBoundary() throws Exception {
        for (String text : new String[]{
                "가".repeat(100),
                "😀".repeat(100)
        }) {
            nextServer().expect(
                            requestTo(BASE + "/" + ID + "/turns/" + TURN)
                    )
                    .andExpect(content().json("{\"message\":\"" + text + "\"}"))
                    .andRespond(
                            withStatus(HttpStatus.ACCEPTED)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .body(turn("PROCESSING", text))
                    );

            mvc.perform(
                            put(PUBLIC + "/turns/" + TURN)
                                    .header("X-Conversation-Token", conversationToken)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"message\":\"" + text + "\"}")
                    )
                    .andExpect(status().isAccepted());
        }
    }

    @Test
    @DisplayName("타인·대화 토큰 누락·로그인 누락은 AI 호출 전에 차단한다")
    void unauthorizedBeforeAi() throws Exception {
        login(8L);

        mvc.perform(
                        get(PUBLIC)
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isNotFound());

        login(7L);

        mvc.perform(get(PUBLIC))
                .andExpect(status().isNotFound());

        SecurityContextHolder.clearContext();

        mvc.perform(
                        get(PUBLIC)
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("카드 블록은 전달하고 잘못된 상품 타입·턴 상태 계약은 503으로 차단한다")
    void responseSchemaValidation() throws Exception {
        String valid = turn("COMPLETED", QUESTION).replace(
                "\"blocks\":[{\"type\":\"text\",\"text\":\"부분 답변\"}]",
                "\"blocks\":[{\"type\":\"cards\",\"products\":["
                        + "{\"productId\":101,\"reason\":\"예산에 맞아요\"}]}]"
        );

        nextServer().expect(requestTo(BASE + "/" + ID + "/turns/" + TURN))
                .andRespond(withSuccess(valid, MediaType.APPLICATION_JSON));

        mvc.perform(
                        get(PUBLIC + "/turns/" + TURN)
                                .header("X-Conversation-Token", conversationToken)
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.blocks[0].products[0].productId").value(101)
                );

        for (String invalid : new String[]{
                valid.replace("\"productId\":101", "\"productId\":\"101\""),
                valid.replace("\"productId\":101", "\"productId\":0"),
                valid.replace("\"lastEventId\":1", "\"lastEventId\":\"1\""),
                turn("FAILED", QUESTION).replace(
                        ",\"error\":{\"message\":\"실패\"}",
                        ""
                )
        }) {
            nextServer().expect(requestTo(BASE + "/" + ID + "/turns/" + TURN))
                    .andRespond(withSuccess(invalid, MediaType.APPLICATION_JSON));

            mvc.perform(
                            get(PUBLIC + "/turns/" + TURN)
                                    .header("X-Conversation-Token", conversationToken)
                    )
                    .andExpect(status().isServiceUnavailable());
        }
    }

    private MockRestServiceServer nextServer() {
        server.verify();
        server.reset();
        return server;
    }

    private void login(long id) {
        Jwt jwt = Jwt.withTokenValue("login-test-token")
                .header("alg", "HS256")
                .subject(Long.toString(id))
                .issuedAt(NOW)
                .expiresAt(NOW.plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt)
        );
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) value;

        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    private static String conversation() {
        return "{\"message\":\"ok\",\"data\":{\"conversationId\":\""
                + ID + "\",\"turns\":[]}}";
    }

    private static String turn(String status, String message) {
        return "{\"message\":\"ok\",\"data\":{\"turnId\":\"" + TURN
                + "\",\"status\":\"" + status
                + "\",\"message\":\"" + message
                + "\",\"blocks\":[{\"type\":\"text\",\"text\":\"부분 답변\"}],"
                + "\"lastEventId\":1"
                + (status.equals("FAILED")
                ? ",\"error\":{\"message\":\"실패\"}"
                : "")
                + "}}";
    }

    private static String error(String code) {
        return "{\"message\":\"error\",\"error\":{\"code\":\"" + code
                + "\",\"traceId\":\"33333333-3333-4333-8333-333333333333\"}}";
    }
}
