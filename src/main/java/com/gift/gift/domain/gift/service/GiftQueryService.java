package com.gift.gift.domain.gift.service;

import java.util.List;

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
import com.gift.gift.domain.gift.repository.GiftQueryRepository;
import com.gift.gift.domain.gift.repository.GiftQueryRow;
import com.gift.gift.domain.gift.support.GiftCursor;
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

    public GiftQueryService(
            GiftQueryRepository giftQueryRepository,
            OpaqueCursorCodec cursorCodec,
            GiftPageAssembler pageAssembler,
            GiftResponseMapper responseMapper
    ) {
        this.giftQueryRepository = giftQueryRepository;
        this.cursorCodec = cursorCodec;
        this.pageAssembler = pageAssembler;
        this.responseMapper = responseMapper;
    }

    public CursorPageResponse<SentGiftListItem> getSentGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findSentGifts(userId, decode(rawCursor)));
        List<SentGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toSentListItem(row, null))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public CursorPageResponse<ReceivedGiftListItem> getReceivedGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findReceivedGifts(userId, decode(rawCursor)));
        List<ReceivedGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toReceivedListItem(row, null))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public GiftSentDetailResponse getSentGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findSentGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(ErrorCode.GIFT_NOT_FOUND));

        return responseMapper.toSentDetail(row, null);
    }

    public GiftReceivedDetailResponse getReceivedGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findReceivedGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(ErrorCode.GIFT_NOT_FOUND));

        return responseMapper.toReceivedDetail(row, null);
    }

    private GiftCursor decode(String rawCursor) {
        return rawCursor == null ? null : cursorCodec.decode(rawCursor, GiftCursor.class);
    }
}
