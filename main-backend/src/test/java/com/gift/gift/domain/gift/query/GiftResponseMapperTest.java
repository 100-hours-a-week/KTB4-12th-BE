package com.gift.gift.domain.gift.query;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.dto.response.GiftReceivedDetailResponse;
import com.gift.gift.domain.gift.dto.response.GiftSentDetailResponse;
import com.gift.gift.domain.gift.dto.response.ReceivedGiftListItem;
import com.gift.gift.domain.gift.dto.response.SentGiftListItem;
import com.gift.gift.domain.gift.repository.GiftQueryRow;

import static org.assertj.core.api.Assertions.assertThat;

class GiftResponseMapperTest {

    private final GiftResponseMapper mapper = new GiftResponseMapper();

    @Test
    @DisplayName("목록 조회 결과를 보낸·받은 선물 DTO로 매핑한다")
    void mapListItems_mapsSnapshotAndCalculatedTotalPrice() {
        GiftQueryRow row = row(null, "상대 사용자");

        SentGiftListItem sent = mapper.toSentListItem(row, "thumbnail");
        ReceivedGiftListItem received = mapper.toReceivedListItem(row, "thumbnail");

        assertThat(sent.recipient().name()).isEqualTo("상대 사용자");
        assertThat(received.sender().name()).isEqualTo("상대 사용자");
        assertThat(sent.product().name()).isEqualTo("선물 상품");
        assertThat(sent.product().thumbnailUrl()).isEqualTo("thumbnail");
        assertThat(sent.totalPrice()).isEqualByComparingTo("20000");
        assertThat(received.totalPrice()).isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("탈퇴한 상대 사용자는 탈퇴한 사용자로 표시한다")
    void mapDetail_masksDeletedCounterpartName() {
        GiftQueryRow row = row(LocalDateTime.now(), "기존 이름");

        GiftSentDetailResponse sent = mapper.toSentDetail(row, null);
        GiftReceivedDetailResponse received = mapper.toReceivedDetail(row, null);

        assertThat(sent.recipient().name()).isEqualTo("탈퇴한 사용자");
        assertThat(received.sender().name()).isEqualTo("탈퇴한 사용자");
        assertThat(sent.product().imageUrl()).isNull();
        assertThat(received.product().imageUrl()).isNull();
    }

    @Test
    @DisplayName("상대 사용자 이름 정보가 없으면 알 수 없음으로 표시한다")
    void mapDetail_usesUnknownName_whenCounterpartNameIsMissing() {
        GiftQueryRow row = row(null, null);

        GiftSentDetailResponse sent = mapper.toSentDetail(row, null);
        GiftReceivedDetailResponse received = mapper.toReceivedDetail(row, null);

        assertThat(sent.recipient().name()).isEqualTo("알 수 없음");
        assertThat(received.sender().name()).isEqualTo("알 수 없음");
    }

    private GiftQueryRow row(LocalDateTime deletedAt, String counterpartName) {
        return new GiftQueryRow(
                1L,
                LocalDateTime.of(2026, 9, 17, 20, 0),
                2L,
                counterpartName,
                deletedAt,
                3L,
                "선물 상품",
                BigDecimal.valueOf(10_000),
                2,
                "브랜드"
        );
    }
}
