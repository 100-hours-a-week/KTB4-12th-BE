package com.gift.gift.domain.gift.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.dto.response.GiftReceivedDetailResponse;
import com.gift.gift.domain.gift.dto.response.GiftSentDetailResponse;
import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.exception.GiftErrorCode;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.query.GiftPage;
import com.gift.gift.domain.gift.query.GiftPageAssembler;
import com.gift.gift.domain.gift.query.GiftResponseMapper;
import com.gift.gift.domain.gift.repository.GiftCountRow;
import com.gift.gift.domain.gift.repository.GiftQueryRepository;
import com.gift.gift.domain.gift.repository.GiftQueryRow;
import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.global.pagination.CursorPageResponse;
import com.gift.gift.global.pagination.OpaqueCursorCodec;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GiftQueryService {

    private final GiftQueryRepository giftQueryRepository;
    private final OpaqueCursorCodec cursorCodec;
    private final GiftPageAssembler pageAssembler;
    private final GiftResponseMapper responseMapper;
    private final ProductQueryService productQueryService;
    private final OrderRepository orderRepository;

    public CursorPageResponse<SentGiftListItem> getSentGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findSentGifts(userId, decode(rawCursor)));
        Map<Long, String> imageUrls = loadImageUrls(page.items());
        Map<Long, String> orderIds = loadOrderIds(page.items());
        List<SentGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toSentListItem(
                        row,
                        requireOrderId(row, orderIds),
                        imageUrls.get(row.productId())
                ))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public CursorPageResponse<ReceivedGiftListItem> getReceivedGifts(Long userId, String rawCursor) {
        GiftPage page = pageAssembler.assemble(giftQueryRepository.findReceivedGifts(userId, decode(rawCursor)));
        Map<Long, String> imageUrls = loadImageUrls(page.items());
        Map<Long, String> orderIds = loadOrderIds(page.items());
        List<ReceivedGiftListItem> items = page.items().stream()
                .map(row -> responseMapper.toReceivedListItem(
                        row,
                        requireOrderId(row, orderIds),
                        imageUrls.get(row.productId())
                ))
                .toList();

        return CursorPageResponse.from(items, page.nextCursor(), page.hasNext());
    }

    public GiftSentDetailResponse getSentGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findSentGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(GiftErrorCode.SENT_GIFT_NOT_FOUND));

        return responseMapper.toSentDetail(
                row,
                requireOrderId(row, loadOrderIds(List.of(row))),
                loadImageUrls(List.of(row)).get(row.productId())
        );
    }

    public GiftReceivedDetailResponse getReceivedGiftDetail(Long userId, Long giftId) {
        GiftQueryRow row = giftQueryRepository.findReceivedGiftDetail(giftId, userId)
                .orElseThrow(() -> new GiftException(GiftErrorCode.RECEIVED_GIFT_NOT_FOUND));

        return responseMapper.toReceivedDetail(
                row,
                requireOrderId(row, loadOrderIds(List.of(row))),
                loadImageUrls(List.of(row)).get(row.productId())
        );
    }

    public GiftCountRow getGiftCount(Long userId) {
        return giftQueryRepository.countSentAndReceivedGifts(userId);
    }

    private Map<Long, String> loadImageUrls(List<GiftQueryRow> rows) {
        List<Long> productIds = rows.stream()
                .map(GiftQueryRow::productId)
                .distinct()
                .toList();

        return productQueryService.findThumbnailUrls(productIds);
    }

    private Map<Long, String> loadOrderIds(List<GiftQueryRow> rows) {
        List<Long> orderRecordIds = rows.stream()
                .map(GiftQueryRow::orderRecordId)
                .distinct()
                .toList();

        return orderRepository.findAllById(orderRecordIds).stream()
                .collect(Collectors.toMap(Order::getId, Order::getOrderId));
    }

    private String requireOrderId(GiftQueryRow row, Map<Long, String> orderIds) {
        String orderId = orderIds.get(row.orderRecordId());
        if (orderId == null) {
            throw new IllegalStateException("선물과 연결된 주문을 찾을 수 없습니다.");
        }
        return orderId;
    }

    private GiftCursor decode(String rawCursor) {
        return rawCursor == null ? null : cursorCodec.decode(rawCursor, GiftCursor.class);
    }
}
