package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.dto.response.GiftReceivedDetailResponse;
import com.gift.gift.domain.gift.dto.response.GiftSentDetailResponse;
import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.query.GiftPage;
import com.gift.gift.domain.gift.query.GiftPageAssembler;
import com.gift.gift.domain.gift.query.GiftResponseMapper;
import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.gift.repository.GiftQueryRepository;
import com.gift.gift.domain.gift.repository.GiftQueryRow;
import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GiftQueryServiceTest {

    private GiftQueryRepository giftQueryRepository;
    private OpaqueCursorCodec cursorCodec;
    private GiftPageAssembler pageAssembler;
    private GiftResponseMapper responseMapper;
    private ProductQueryService productQueryService;
    private GiftQueryService giftQueryService;

    @BeforeEach
    void setUp() {
        giftQueryRepository = mock(GiftQueryRepository.class);
        cursorCodec = mock(OpaqueCursorCodec.class);
        pageAssembler = mock(GiftPageAssembler.class);
        responseMapper = mock(GiftResponseMapper.class);
        productQueryService = mock(ProductQueryService.class);
        giftQueryService = new GiftQueryService(
                giftQueryRepository,
                cursorCodec,
                pageAssembler,
                responseMapper,
                productQueryService
        );
    }

    @Test
    @DisplayName("보낸 선물 목록은 커서를 해석하고 페이지 정보와 매핑 결과를 반환한다")
    void getSentGifts_decodesCursorAndReturnsMappedPage() {
        String rawCursor = "cursor";
        GiftCursor cursor = new GiftCursor(LocalDateTime.of(2026, 9, 17, 20, 0), 10L);
        GiftQueryRow row = row();
        SentGiftListItem item = mock(SentGiftListItem.class);
        when(cursorCodec.decode(rawCursor, GiftCursor.class)).thenReturn(cursor);
        when(giftQueryRepository.findSentGifts(1L, cursor)).thenReturn(List.of(row));
        when(pageAssembler.assemble(List.of(row))).thenReturn(new GiftPage(List.of(row), true, "next"));
        when(productQueryService.findThumbnailUrls(List.of(3L)))
                .thenReturn(Map.of(3L, "https://image.example/main.jpg"));
        when(responseMapper.toSentListItem(row, "https://image.example/main.jpg")).thenReturn(item);

        CursorPageResponse<SentGiftListItem> result = giftQueryService.getSentGifts(1L, rawCursor);

        assertThat(result.items()).containsExactly(item);
        assertThat(result.pagination().hasNext()).isTrue();
        assertThat(result.pagination().nextCursor()).isEqualTo("next");
        verify(cursorCodec).decode(rawCursor, GiftCursor.class);
    }

    @Test
    @DisplayName("받은 선물 목록은 상품 대표 이미지 URL을 매핑한다")
    void getReceivedGifts_mapsProductThumbnailUrl() {
        GiftQueryRow row = row();
        ReceivedGiftListItem item = mock(ReceivedGiftListItem.class);
        when(giftQueryRepository.findReceivedGifts(1L, null)).thenReturn(List.of(row));
        when(pageAssembler.assemble(List.of(row))).thenReturn(new GiftPage(List.of(row), false, null));
        when(productQueryService.findThumbnailUrls(List.of(3L)))
                .thenReturn(Map.of(3L, "https://image.example/main.jpg"));
        when(responseMapper.toReceivedListItem(row, "https://image.example/main.jpg")).thenReturn(item);

        CursorPageResponse<ReceivedGiftListItem> result = giftQueryService.getReceivedGifts(1L, null);

        assertThat(result.items()).containsExactly(item);
    }

    @Test
    @DisplayName("보낸 선물 상세는 상품 대표 이미지 URL을 매핑한다")
    void getSentGiftDetail_mapsProductImageUrl() {
        GiftQueryRow row = row();
        GiftSentDetailResponse response = mock(GiftSentDetailResponse.class);
        when(giftQueryRepository.findSentGiftDetail(10L, 1L)).thenReturn(Optional.of(row));
        when(productQueryService.findThumbnailUrls(List.of(3L)))
                .thenReturn(Map.of(3L, "https://image.example/main.jpg"));
        when(responseMapper.toSentDetail(row, "https://image.example/main.jpg")).thenReturn(response);

        assertThat(giftQueryService.getSentGiftDetail(1L, 10L)).isSameAs(response);
    }

    @Test
    @DisplayName("받은 선물 상세는 상품 이미지가 없으면 null URL을 매핑한다")
    void getReceivedGiftDetail_mapsNull_whenProductHasNoImage() {
        GiftQueryRow row = row();
        GiftReceivedDetailResponse response = mock(GiftReceivedDetailResponse.class);
        when(giftQueryRepository.findReceivedGiftDetail(10L, 1L)).thenReturn(Optional.of(row));
        when(productQueryService.findThumbnailUrls(List.of(3L)))
                .thenReturn(Collections.singletonMap(3L, null));
        when(responseMapper.toReceivedDetail(row, null)).thenReturn(response);

        assertThat(giftQueryService.getReceivedGiftDetail(1L, 10L)).isSameAs(response);
    }

    @Test
    @DisplayName("보낸 선물 상세가 없으면 보낸 선물 전용 메시지로 GIFT_NOT_FOUND가 발생한다")
    void getSentGiftDetail_throwsGiftNotFound_whenGiftDoesNotExist() {
        when(giftQueryRepository.findSentGiftDetail(10L, 1L)).thenReturn(Optional.empty());

        assertGiftNotFound(
                () -> giftQueryService.getSentGiftDetail(1L, 10L),
                "보낸 선물 내역을 찾을 수 없습니다."
        );
    }

    @Test
    @DisplayName("받은 선물 상세가 없으면 받은 선물 전용 메시지로 GIFT_NOT_FOUND가 발생한다")
    void getReceivedGiftDetail_throwsGiftNotFound_whenGiftDoesNotExist() {
        when(giftQueryRepository.findReceivedGiftDetail(10L, 1L)).thenReturn(Optional.empty());

        assertGiftNotFound(
                () -> giftQueryService.getReceivedGiftDetail(1L, 10L),
                "받은 선물 내역을 찾을 수 없습니다."
        );
    }

    @Test
    @DisplayName("올해 선물 건수는 해당 연도 1월 1일부터 요청일 당일까지의 범위로 조회한다")
    void getYearlyGiftCount_queriesFromStartOfYearThroughToday() {
        when(giftQueryRepository.countSentAndReceivedGifts(
                1L,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 9, 22, 0, 0)
        )).thenReturn(new GiftCountRow(3L, 5L));

        GiftCountRow result = giftQueryService.getYearlyGiftCount(1L, LocalDate.of(2026, 9, 21));

        assertThat(result.sentCount()).isEqualTo(3L);
        assertThat(result.receivedCount()).isEqualTo(5L);
    }

    private void assertGiftNotFound(Runnable action, String message) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(GiftException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.GIFT_NOT_FOUND);
                    assertThat(exception.getMessage()).isEqualTo(message);
                });
    }

    private GiftQueryRow row() {
        return new GiftQueryRow(
                10L,
                LocalDateTime.of(2026, 9, 17, 20, 0),
                2L,
                "상대 사용자",
                null,
                3L,
                "선물 상품",
                BigDecimal.valueOf(10_000),
                2,
                "브랜드"
        );
    }
}
