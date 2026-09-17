package com.gift.gift.domain.gift.service;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GiftServiceTest {

    private GiftHistoryRepository giftHistoryRepository;
    private GiftService giftService;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        giftService = new GiftService(giftHistoryRepository);
    }

    @Test
    @DisplayName("멱등 키에 해당하는 기존 선물 이력이 없으면 빈 결과를 반환한다")
    void findExistingGift_returnsEmpty_whenGiftDoesNotExist() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.empty());

        Optional<GiftHistory> result = giftService.findExistingGift(senderId, idempotencyKey, "a".repeat(64));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("멱등 키와 fingerprint가 같으면 기존 선물 이력을 반환한다")
    void findExistingGift_returnsGift_whenFingerprintMatches() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftHistory giftHistory = mock(GiftHistory.class);
        when(giftHistory.getRequestFingerprint()).thenReturn(fingerprint);
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(giftHistory));

        Optional<GiftHistory> result = giftService.findExistingGift(senderId, idempotencyKey, fingerprint);

        assertThat(result).containsSame(giftHistory);
    }

    @Test
    @DisplayName("같은 멱등 키를 다른 요청에 사용하면 IDEMPOTENCY_KEY_CONFLICT가 발생한다")
    void findExistingGift_throwsConflict_whenFingerprintDiffers() {
        Long senderId = 1L;
        UUID idempotencyKey = UUID.randomUUID();
        GiftHistory giftHistory = mock(GiftHistory.class);
        when(giftHistory.getRequestFingerprint()).thenReturn("a".repeat(64));
        when(giftHistoryRepository.findBySender_IdAndIdempotencyKey(senderId, idempotencyKey))
                .thenReturn(Optional.of(giftHistory));

        assertThatThrownBy(() -> giftService.findExistingGift(senderId, idempotencyKey, "b".repeat(64)))
                .isInstanceOfSatisfying(GiftException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));
    }
}
