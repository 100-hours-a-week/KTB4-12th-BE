package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftCreateRequest;
import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.product.service.ProductStockService;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GiftCommandServiceTest {

    private static final Long SENDER_ID = 1L;
    private static final Long RECIPIENT_ID = 2L;
    private static final Long PRODUCT_ID = 3L;

    private GiftHistoryRepository giftHistoryRepository;
    private UserQueryService userQueryService;
    private FriendQueryService friendQueryService;
    private ProductQueryService productQueryService;
    private ProductStockService productStockService;
    private GiftCommandService giftCommandService;

    @BeforeEach
    void setUp() {
        giftHistoryRepository = mock(GiftHistoryRepository.class);
        userQueryService = mock(UserQueryService.class);
        friendQueryService = mock(FriendQueryService.class);
        productQueryService = mock(ProductQueryService.class);
        productStockService = mock(ProductStockService.class);
        giftCommandService = new GiftCommandService(
                giftHistoryRepository,
                userQueryService,
                friendQueryService,
                productQueryService,
                productStockService
        );
    }

    @Test
    @DisplayName("재검증과 재고 차감을 통과하면 GiftHistory를 저장하고 반환한다")
    void createNewGift_savesAndReturnsGiftHistory_whenConditionsAreValid() {
        UUID idempotencyKey = UUID.randomUUID();
        String fingerprint = "a".repeat(64);
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 2, BigDecimal.valueOf(32_000));
        Product product = mock(Product.class);
        User senderRef = mock(User.class);
        User recipientRef = mock(User.class);

        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getName()).thenReturn("이니스프리 그린티 수분 크림");
        when(productStockService.deductStockIfAvailable(PRODUCT_ID, 2)).thenReturn(true);
        when(userQueryService.getReference(SENDER_ID)).thenReturn(senderRef);
        when(userQueryService.getReference(RECIPIENT_ID)).thenReturn(recipientRef);
        when(giftHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        GiftHistory result = giftCommandService.createNewGift(SENDER_ID, idempotencyKey, fingerprint, request);

        assertThat(result.getSender()).isSameAs(senderRef);
        assertThat(result.getRecipient()).isSameAs(recipientRef);
        assertThat(result.getProduct()).isSameAs(product);
        assertThat(result.getQuantity()).isEqualTo(2);
        assertThat(result.getProductPriceSnapshot()).isEqualByComparingTo(BigDecimal.valueOf(32_000));
        assertThat(result.getProductNameSnapshot()).isEqualTo("이니스프리 그린티 수분 크림");
        assertThat(result.getIdempotencyKey()).isEqualTo(idempotencyKey);
        assertThat(result.getRequestFingerprint()).isEqualTo(fingerprint);

        var order = inOrder(userQueryService, friendQueryService, productQueryService, productStockService,
                giftHistoryRepository);
        order.verify(userQueryService).findActiveUser(RECIPIENT_ID);
        order.verify(friendQueryService).areFriends(SENDER_ID, RECIPIENT_ID);
        order.verify(productQueryService).findAvailableProduct(PRODUCT_ID);
        order.verify(productStockService).deductStockIfAvailable(PRODUCT_ID, 2);
        order.verify(giftHistoryRepository).save(any());
    }

    @Test
    @DisplayName("자기 자신에게 선물하면 GIFT_CANNOT_SEND_TO_SELF가 발생하고 아무것도 조회하지 않는다")
    void createNewGift_throwsGiftCannotSendToSelf_whenSendingToSelf() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, SENDER_ID, 1, BigDecimal.valueOf(32_000));

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.GIFT_CANNOT_SEND_TO_SELF
        );

        verifyNoInteractions(userQueryService, friendQueryService, productQueryService, productStockService,
                giftHistoryRepository);
    }

    @Test
    @DisplayName("활성 수신자가 없으면 RECIPIENT_NOT_FOUND가 발생한다")
    void createNewGift_throwsRecipientNotFound_whenRecipientIsNotActive() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 1, BigDecimal.valueOf(32_000));
        when(userQueryService.findActiveUser(RECIPIENT_ID)).thenReturn(Optional.empty());

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.RECIPIENT_NOT_FOUND
        );

        verifyNoInteractions(friendQueryService, productQueryService, productStockService, giftHistoryRepository);
    }

    @Test
    @DisplayName("수신자가 친구가 아니면 RECIPIENT_NOT_FRIEND가 발생한다")
    void createNewGift_throwsRecipientNotFriend_whenRelationshipDoesNotExist() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 1, BigDecimal.valueOf(32_000));
        when(userQueryService.findActiveUser(RECIPIENT_ID))
                .thenReturn(Optional.of(new ActiveUserSummary(RECIPIENT_ID, "수신자")));
        when(friendQueryService.areFriends(SENDER_ID, RECIPIENT_ID)).thenReturn(false);

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.RECIPIENT_NOT_FRIEND
        );

        verifyNoInteractions(productQueryService, productStockService, giftHistoryRepository);
    }

    @Test
    @DisplayName("활성 상품이 없으면 PRODUCT_NOT_FOUND가 발생한다")
    void createNewGift_throwsProductNotFound_whenProductDoesNotExist() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 1, BigDecimal.valueOf(32_000));
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.empty());

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.PRODUCT_NOT_FOUND
        );

        verifyNoInteractions(productStockService, giftHistoryRepository);
    }

    @Test
    @DisplayName("현재 단가가 요청의 expectedUnitPrice와 다르면 GIFT_CONDITIONS_CHANGED가 발생한다")
    void createNewGift_throwsGiftConditionsChanged_whenPriceDiffers() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 1, BigDecimal.valueOf(32_000));
        Product product = mock(Product.class);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(35_000));

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.GIFT_CONDITIONS_CHANGED
        );

        verifyNoInteractions(productStockService, giftHistoryRepository);
    }

    @Test
    @DisplayName("조건부 재고 차감이 실패하면 INSUFFICIENT_STOCK이 발생한다")
    void createNewGift_throwsInsufficientStock_whenStockDeductionFails() {
        GiftCreateRequest request = new GiftCreateRequest(PRODUCT_ID, RECIPIENT_ID, 5, BigDecimal.valueOf(32_000));
        Product product = mock(Product.class);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(productStockService.deductStockIfAvailable(PRODUCT_ID, 5)).thenReturn(false);

        assertGiftError(
                () -> giftCommandService.createNewGift(SENDER_ID, UUID.randomUUID(), "a".repeat(64), request),
                ErrorCode.INSUFFICIENT_STOCK
        );

        verifyNoInteractions(giftHistoryRepository);
    }

    private void stubValidRecipientAndFriend() {
        when(userQueryService.findActiveUser(RECIPIENT_ID))
                .thenReturn(Optional.of(new ActiveUserSummary(RECIPIENT_ID, "수신자")));
        when(friendQueryService.areFriends(SENDER_ID, RECIPIENT_ID)).thenReturn(true);
    }

    private void assertGiftError(Runnable action, ErrorCode expectedErrorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(GiftException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(expectedErrorCode));
    }
}
