package com.gift.gift.domain.gift.query;

import java.math.BigDecimal;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.gift.gift.domain.gift.dto.response.GiftReceivedDetailResponse;
import com.gift.gift.domain.gift.dto.response.GiftSentDetailResponse;
import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.repository.GiftQueryRow;

@Component
public class GiftResponseMapper {

    private static final String DELETED_USER_NAME = "탈퇴한 사용자";
    private static final String UNKNOWN_USER_NAME = "알 수 없음";

    public SentGiftListItem toSentListItem(GiftQueryRow row, String thumbnailUrl) {
        Objects.requireNonNull(row, "선물 조회 결과는 필수입니다.");

        return SentGiftListItem.from(
                row.giftId(),
                row.completedAt(),
                new SentGiftListItem.Recipient(row.counterpartUserId(), counterpartName(row)),
                new SentGiftListItem.Product(
                        row.productId(),
                        row.productNameSnapshot(),
                        row.productBrand(),
                        thumbnailUrl
                ),
                row.quantity(),
                row.productPriceSnapshot(),
                totalPrice(row)
        );
    }

    public ReceivedGiftListItem toReceivedListItem(GiftQueryRow row, String thumbnailUrl) {
        Objects.requireNonNull(row, "선물 조회 결과는 필수입니다.");

        return ReceivedGiftListItem.from(
                row.giftId(),
                row.completedAt(),
                new ReceivedGiftListItem.Sender(row.counterpartUserId(), counterpartName(row)),
                new ReceivedGiftListItem.Product(
                        row.productId(),
                        row.productNameSnapshot(),
                        row.productBrand(),
                        thumbnailUrl
                ),
                row.quantity(),
                row.productPriceSnapshot(),
                totalPrice(row)
        );
    }

    public GiftSentDetailResponse toSentDetail(GiftQueryRow row, String imageUrl) {
        Objects.requireNonNull(row, "선물 조회 결과는 필수입니다.");

        return GiftSentDetailResponse.from(
                row.giftId(),
                row.completedAt(),
                new GiftSentDetailResponse.Recipient(row.counterpartUserId(), counterpartName(row)),
                new GiftSentDetailResponse.Product(
                        row.productId(),
                        row.productNameSnapshot(),
                        row.productBrand(),
                        imageUrl
                ),
                row.quantity(),
                row.productPriceSnapshot(),
                totalPrice(row)
        );
    }

    public GiftReceivedDetailResponse toReceivedDetail(GiftQueryRow row, String imageUrl) {
        Objects.requireNonNull(row, "선물 조회 결과는 필수입니다.");

        return GiftReceivedDetailResponse.from(
                row.giftId(),
                row.completedAt(),
                new GiftReceivedDetailResponse.Sender(row.counterpartUserId(), counterpartName(row)),
                new GiftReceivedDetailResponse.Product(
                        row.productId(),
                        row.productNameSnapshot(),
                        row.productBrand(),
                        imageUrl
                ),
                row.quantity(),
                row.productPriceSnapshot(),
                totalPrice(row)
        );
    }

    private String counterpartName(GiftQueryRow row) {
        if (row.counterpartDeletedAt() != null) {
            return DELETED_USER_NAME;
        }

        return row.counterpartName() == null ? UNKNOWN_USER_NAME : row.counterpartName();
    }

    private BigDecimal totalPrice(GiftQueryRow row) {
        return row.productPriceSnapshot().multiply(BigDecimal.valueOf(row.quantity()));
    }
}
