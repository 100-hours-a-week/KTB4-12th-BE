package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class GiftServiceTest {

    private GiftHistoryRepository giftHistoryRepository;
    private UserQueryService userQueryService;
    private FriendQueryService friendQueryService;
    private ProductQueryService productQueryService;
    private GiftService giftService;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        userQueryService = mock(UserQueryService.class);
        friendQueryService = mock(FriendQueryService.class);
        productQueryService = mock(ProductQueryService.class);
        giftService = new GiftService(
                giftHistoryRepository,
                userQueryService,
                friendQueryService,
                productQueryService
        );
    }

    @Test
    @DisplayName("사전 검증 성공 시 수신자·상품·가격·최대 주문 수량을 반환한다")
    void preflight_returnsCalculatedResponse_whenConditionsAreValid() {
        Long senderId = 1L;
        Long recipientId = 2L;
        Long productId = 3L;
        Product product = mock(Product.class);
        GiftPreflightRequest request = new GiftPreflightRequest(productId, recipientId, 2);

        when(userQueryService.findActiveUser(recipientId))
                .thenReturn(Optional.of(new ActiveUserSummary(recipientId, "수신자")));
        when(friendQueryService.areFriends(senderId, recipientId)).thenReturn(true);
        when(productQueryService.findAvailableProduct(productId)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(productId);
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getQuantity()).thenReturn(15);

        GiftPreflightResponse response = giftService.preflight(senderId, request);

        assertThat(response.recipient())
                .isEqualTo(new GiftPreflightResponse.Recipient(recipientId, "수신자"));
        assertThat(response.product()).isEqualTo(new GiftPreflightResponse.Product(
                productId,
                BigDecimal.valueOf(32_000),
                2,
                BigDecimal.valueOf(64_000),
                10
        ));
        assertThat(response.preferenceWarning()).isNull();

        var order = inOrder(userQueryService, friendQueryService, productQueryService);
        order.verify(userQueryService).findActiveUser(recipientId);
        order.verify(friendQueryService).areFriends(senderId, recipientId);
        order.verify(productQueryService).findAvailableProduct(productId);
    }

    @Test
    @DisplayName("자기 자신에게 선물하면 INVALID_REQUEST가 발생하고 조회하지 않는다")
    void preflight_throwsInvalidRequest_whenSendingToSelf() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 1L, 1);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.INVALID_REQUEST);

        verifyNoInteractions(userQueryService, friendQueryService, productQueryService);
    }

    @Test
    @DisplayName("활성 수신자가 없으면 RECIPIENT_NOT_FOUND가 발생한다")
    void preflight_throwsRecipientNotFound_whenRecipientIsNotActive() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L)).thenReturn(Optional.empty());

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FOUND);

        verifyNoInteractions(friendQueryService, productQueryService);
    }

    @Test
    @DisplayName("수신자가 친구가 아니면 RECIPIENT_NOT_FRIEND가 발생한다")
    void preflight_throwsRecipientNotFriend_whenRelationshipDoesNotExist() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L))
                .thenReturn(Optional.of(new ActiveUserSummary(2L, "수신자")));
        when(friendQueryService.areFriends(1L, 2L)).thenReturn(false);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FRIEND);

        verifyNoInteractions(productQueryService);
    }

    @Test
    @DisplayName("상품이 없으면 PRODUCT_NOT_FOUND가 발생한다")
    void preflight_throwsProductNotFound_whenProductDoesNotExist() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.empty());

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.PRODUCT_NOT_FOUND);
    }

    @Test
    @DisplayName("상품 재고가 요청 수량보다 적으면 INSUFFICIENT_STOCK이 발생한다")
    void preflight_throwsInsufficientStock_whenQuantityExceedsStock() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 3);
        Product product = mock(Product.class);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.of(product));
        when(product.getQuantity()).thenReturn(2);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.INSUFFICIENT_STOCK);
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

    private void stubValidRecipientAndFriend() {
        when(userQueryService.findActiveUser(2L))
                .thenReturn(Optional.of(new ActiveUserSummary(2L, "수신자")));
        when(friendQueryService.areFriends(1L, 2L)).thenReturn(true);
    }

    private void assertGiftError(Runnable action, ErrorCode expectedErrorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(GiftException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));
    }
}
