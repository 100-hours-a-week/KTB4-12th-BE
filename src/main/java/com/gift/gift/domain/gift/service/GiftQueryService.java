package com.gift.gift.domain.gift.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.gift.gift.domain.product.query.ProductThumbnailMapper;
import com.gift.gift.domain.product.repository.ProductImageProjection;
import com.gift.gift.domain.product.repository.ProductImageRepository;
import com.gift.gift.global.exception.ErrorCode;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@Transactional(readOnly = true)
public class GiftQueryService {

    private final GiftQueryRepository giftQueryRepository;
    private final OpaqueCursorCodec cursorCodec;
    private final GiftPageAssembler pageAssembler;
    private final GiftResponseMapper responseMapper;
    private final ProductImageRepository productImageRepository;
    private final ProductThumbnailMapper productThumbnailMapper;

    public GiftQueryService(
            GiftQueryRepository giftQueryRepository,
            OpaqueCursorCodec cursorCodec,
            GiftPageAssembler pageAssembler,
            GiftResponseMapper responseMapper,
            ProductImageRepository productImageRepository,
            ProductThumbnailMapper productThumbnailMapper
    ) {
        this.giftQueryRepository = giftQueryRepository;
        this.cursorCodec = cursorCodec;
        this.pageAssembler = pageAssembler;
        this.responseMapper = responseMapper;
        this.productImageRepository = productImageRepository;
        this.productThumbnailMapper = productThumbnailMapper;
    }

    public CursorPageResponse<SentGiftListItem> getSentGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findSentGifts(userId, decode(rawCursor)));
        Map<Long, String> imageUrls = loadImageUrls(page.items());
        List<SentGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toSentListItem(row, imageUrls.get(row.productId())))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public CursorPageResponse<ReceivedGiftListItem> getReceivedGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findReceivedGifts(userId, decode(rawCursor)));
        Map<Long, String> imageUrls = loadImageUrls(page.items());
        List<ReceivedGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toReceivedListItem(row, imageUrls.get(row.productId())))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public GiftSentDetailResponse getSentGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findSentGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(
                        ErrorCode.GIFT_NOT_FOUND,
                        "보낸 선물 내역을 찾을 수 없습니다."
                ));

        return responseMapper.toSentDetail(row, loadImageUrls(List.of(row)).get(row.productId()));
    }

    public GiftReceivedDetailResponse getReceivedGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findReceivedGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(
                        ErrorCode.GIFT_NOT_FOUND,
                        "받은 선물 내역을 찾을 수 없습니다."
                ));

        return responseMapper.toReceivedDetail(row, loadImageUrls(List.of(row)).get(row.productId()));
    }

    public GiftCountRow getYearlyGiftCount(Long userId, LocalDate today) {
        return giftQueryRepository.countSentAndReceivedGifts(
                userId,
                today.withDayOfYear(1).atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
    }

    private Map<Long, String> loadImageUrls(List<GiftQueryRow> rows) {
        List<Long> productIds = rows.stream()
                .map(GiftQueryRow::productId)
                .distinct()
                .toList();
        List<ProductImageProjection> images = productIds.isEmpty()
                ? List.of()
                : productImageRepository.findThumbnailCandidates(productIds);

        return productThumbnailMapper.mapUrls(productIds, images);
    }

    private GiftCursor decode(String rawCursor) {
        return rawCursor == null ? null : cursorCodec.decode(rawCursor, GiftCursor.class);
    }
}
