package com.gift.gift.domain.gift.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import com.gift.gift.domain.gift.dto.response.GiftReceivedDetailResponse;
import com.gift.gift.domain.gift.dto.response.GiftSentDetailResponse;
import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.service.GiftQueryService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.exception.GlobalExceptionHandler;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.InvalidCursorException;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GiftControllerTest {

    private static final Long USER_ID = 1L;
    private static final LocalDateTime COMPLETED_AT = LocalDateTime.of(2026, 8, 29, 14, 20);

    private GiftQueryService giftQueryService;
    private MockMvc mockMvc;
    private LocalValidatorFactoryBean validator;

    @BeforeEach
    void setUp() {
        giftQueryService = mock(GiftQueryService.class);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new GiftController(giftQueryService))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();

        Jwt jwt = Jwt.withTokenValue("access-token")
                .header("alg", "HS256")
                .subject(USER_ID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .issuer("https://test-issuer.example")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(
                        jwt,
                        List.of(),
                        USER_ID.toString()
                )
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        validator.close();
    }

    @Test
    @DisplayName("보낸 선물 목록은 인증 사용자와 커서로 조회하고 페이지 정보를 반환한다")
    void getSentGifts_returnsPage_forAuthenticatedUser() throws Exception {
        when(giftQueryService.getSentGifts(USER_ID, "cursor-1"))
                .thenReturn(CursorPageResponse.from(List.of(sentListItem()), "next-cursor", true));

        mockMvc.perform(get("/gifts/sent").param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("보낸 선물 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].giftId").value(301))
                .andExpect(jsonPath("$.data.items[0].recipient.name").value("김민정"))
                .andExpect(jsonPath("$.data.items[0].product.thumbnailUrl").value("https://cdn.example.com/51.jpg"))
                .andExpect(jsonPath("$.data.items[0].totalPrice").value(70000))
                .andExpect(jsonPath("$.data.pagination.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(giftQueryService).getSentGifts(USER_ID, "cursor-1");
        verifyNoMoreInteractions(giftQueryService);
    }

    @Test
    @DisplayName("보낸 선물이 없으면 빈 목록 전용 메시지와 첫 페이지 조회로 응답한다")
    void getSentGifts_returnsEmptyMessage_whenNoGifts() throws Exception {
        when(giftQueryService.getSentGifts(USER_ID, null))
                .thenReturn(CursorPageResponse.from(List.of(), null, false));

        mockMvc.perform(get("/gifts/sent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("보낸 선물 내역이 없습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.pagination.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(false));

        verify(giftQueryService).getSentGifts(USER_ID, null);
    }

    @Test
    @DisplayName("요청의 userId 파라미터는 무시하고 인증 사용자로만 조회한다")
    void getSentGifts_ignoresUserIdRequestParameter() throws Exception {
        when(giftQueryService.getSentGifts(USER_ID, null))
                .thenReturn(CursorPageResponse.from(List.of(), null, false));

        mockMvc.perform(get("/gifts/sent").param("userId", "99"))
                .andExpect(status().isOk());

        verify(giftQueryService).getSentGifts(USER_ID, null);
        verifyNoMoreInteractions(giftQueryService);
    }

    @Test
    @DisplayName("받은 선물 목록은 인증 사용자와 커서로 조회하고 페이지 정보를 반환한다")
    void getReceivedGifts_returnsPage_forAuthenticatedUser() throws Exception {
        when(giftQueryService.getReceivedGifts(USER_ID, "cursor-1"))
                .thenReturn(CursorPageResponse.from(List.of(receivedListItem()), "next-cursor", true));

        mockMvc.perform(get("/gifts/received").param("cursor", "cursor-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("받은 선물 목록을 조회했습니다."))
                .andExpect(jsonPath("$.data.items[0].giftId").value(410))
                .andExpect(jsonPath("$.data.items[0].sender.name").value("박선물"))
                .andExpect(jsonPath("$.data.items[0].product.thumbnailUrl").value("https://cdn.example.com/51.jpg"))
                .andExpect(jsonPath("$.data.pagination.nextCursor").value("next-cursor"))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(giftQueryService).getReceivedGifts(USER_ID, "cursor-1");
        verifyNoMoreInteractions(giftQueryService);
    }

    @Test
    @DisplayName("받은 선물이 없으면 빈 목록 전용 메시지와 첫 페이지 조회로 응답한다")
    void getReceivedGifts_returnsEmptyMessage_whenNoGifts() throws Exception {
        when(giftQueryService.getReceivedGifts(USER_ID, null))
                .thenReturn(CursorPageResponse.from(List.of(), null, false));

        mockMvc.perform(get("/gifts/received"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("받은 선물 내역이 없습니다."))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.pagination.nextCursor").value(nullValue()))
                .andExpect(jsonPath("$.data.pagination.hasNext").value(false));

        verify(giftQueryService).getReceivedGifts(USER_ID, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/gifts/sent", "/gifts/received"})
    @DisplayName("잘못된 커서는 400 INVALID_CURSOR와 커서 확인 메시지를 반환한다")
    void getGifts_returnsInvalidCursor_whenCursorIsMalformed(String path) throws Exception {
        when(giftQueryService.getSentGifts(USER_ID, "bad")).thenThrow(new InvalidCursorException());
        when(giftQueryService.getReceivedGifts(USER_ID, "bad")).thenThrow(new InvalidCursorException());

        mockMvc.perform(get(path).param("cursor", "bad"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("커서를 확인해 주세요."))
                .andExpect(jsonPath("$.error.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("보낸 선물 상세는 인증 사용자와 giftId로 조회한다")
    void getSentGiftDetail_returnsDetail_forAuthenticatedUser() throws Exception {
        when(giftQueryService.getSentGiftDetail(USER_ID, 301L)).thenReturn(sentDetail());

        mockMvc.perform(get("/gifts/sent/301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("보낸 선물 상세 정보를 조회했습니다."))
                .andExpect(jsonPath("$.data.giftId").value(301))
                .andExpect(jsonPath("$.data.recipient.name").value("김민정"))
                .andExpect(jsonPath("$.data.product.imageUrl").value("https://cdn.example.com/51.jpg"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(giftQueryService).getSentGiftDetail(USER_ID, 301L);
        verifyNoMoreInteractions(giftQueryService);
    }

    @Test
    @DisplayName("받은 선물 상세는 인증 사용자와 giftId로 조회한다")
    void getReceivedGiftDetail_returnsDetail_forAuthenticatedUser() throws Exception {
        when(giftQueryService.getReceivedGiftDetail(USER_ID, 410L)).thenReturn(receivedDetail());

        mockMvc.perform(get("/gifts/received/410"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("받은 선물 상세 정보를 조회했습니다."))
                .andExpect(jsonPath("$.data.giftId").value(410))
                .andExpect(jsonPath("$.data.sender.name").value("박선물"))
                .andExpect(jsonPath("$.data.product.imageUrl").value("https://cdn.example.com/51.jpg"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(giftQueryService).getReceivedGiftDetail(USER_ID, 410L);
        verifyNoMoreInteractions(giftQueryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    @DisplayName("보낸 선물 상세의 giftId가 양수가 아니면 400 INVALID_REQUEST를 반환한다")
    void getSentGiftDetail_returnsInvalidRequest_whenGiftIdIsInvalid(String giftId) throws Exception {
        mockMvc.perform(get("/gifts/sent/{giftId}", giftId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(giftQueryService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "abc"})
    @DisplayName("받은 선물 상세의 giftId가 양수가 아니면 400 INVALID_REQUEST를 반환한다")
    void getReceivedGiftDetail_returnsInvalidRequest_whenGiftIdIsInvalid(String giftId) throws Exception {
        mockMvc.perform(get("/gifts/received/{giftId}", giftId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        verifyNoInteractions(giftQueryService);
    }

    @Test
    @DisplayName("보낸 선물 상세가 없으면 404 GIFT_NOT_FOUND와 예외 메시지를 반환한다")
    void getSentGiftDetail_returnsNotFound_whenGiftDoesNotExist() throws Exception {
        when(giftQueryService.getSentGiftDetail(USER_ID, 301L))
                .thenThrow(new GiftException(ErrorCode.GIFT_NOT_FOUND, "보낸 선물 내역을 찾을 수 없습니다."));

        mockMvc.perform(get("/gifts/sent/301"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("보낸 선물 내역을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code").value("GIFT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("받은 선물 상세가 없으면 404 GIFT_NOT_FOUND와 예외 메시지를 반환한다")
    void getReceivedGiftDetail_returnsNotFound_whenGiftDoesNotExist() throws Exception {
        when(giftQueryService.getReceivedGiftDetail(USER_ID, 410L))
                .thenThrow(new GiftException(ErrorCode.GIFT_NOT_FOUND, "받은 선물 내역을 찾을 수 없습니다."));

        mockMvc.perform(get("/gifts/received/410"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("받은 선물 내역을 찾을 수 없습니다."))
                .andExpect(jsonPath("$.error.code").value("GIFT_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private SentGiftListItem sentListItem() {
        return SentGiftListItem.from(
                301L,
                COMPLETED_AT,
                new SentGiftListItem.Recipient(27L, "김민정"),
                new SentGiftListItem.Product(51L, "오 드 퍼퓸 50ml", "Example Brand", "https://cdn.example.com/51.jpg"),
                2,
                BigDecimal.valueOf(35000),
                BigDecimal.valueOf(70000)
        );
    }

    private ReceivedGiftListItem receivedListItem() {
        return ReceivedGiftListItem.from(
                410L,
                COMPLETED_AT,
                new ReceivedGiftListItem.Sender(12L, "박선물"),
                new ReceivedGiftListItem.Product(51L, "오 드 퍼퓸 50ml", "Example Brand", "https://cdn.example.com/51.jpg"),
                2,
                BigDecimal.valueOf(35000),
                BigDecimal.valueOf(70000)
        );
    }

    private GiftSentDetailResponse sentDetail() {
        return GiftSentDetailResponse.from(
                301L,
                COMPLETED_AT,
                new GiftSentDetailResponse.Recipient(27L, "김민정"),
                new GiftSentDetailResponse.Product(
                        51L, "오 드 퍼퓸 50ml", "Example Brand", "https://cdn.example.com/51.jpg"
                ),
                2,
                BigDecimal.valueOf(35000),
                BigDecimal.valueOf(70000)
        );
    }

    private GiftReceivedDetailResponse receivedDetail() {
        return GiftReceivedDetailResponse.from(
                410L,
                COMPLETED_AT,
                new GiftReceivedDetailResponse.Sender(12L, "박선물"),
                new GiftReceivedDetailResponse.Product(
                        51L, "오 드 퍼퓸 50ml", "Example Brand", "https://cdn.example.com/51.jpg"
                ),
                2,
                BigDecimal.valueOf(35000),
                BigDecimal.valueOf(70000)
        );
    }
}
