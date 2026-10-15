package com.gift.gift.domain.gift.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.gift.dto.request.GiftPreflightRequest;
import com.gift.gift.domain.gift.dto.response.GiftPreflightResponse;
import com.gift.gift.domain.gift.exception.GiftException;
import com.gift.gift.domain.preference.dto.response.PreferenceWarningResult;
import com.gift.gift.domain.preference.service.PreferenceQueryService;
import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GiftServiceTest {

    private UserQueryService userQueryService;
    private FriendQueryService friendQueryService;
    private ProductQueryService productQueryService;
    private PreferenceQueryService preferenceQueryService;
    private GiftService giftService;

    @BeforeEach
    void setUp() {
        userQueryService = mock(UserQueryService.class);
        friendQueryService = mock(FriendQueryService.class);
        productQueryService = mock(ProductQueryService.class);
        preferenceQueryService = mock(PreferenceQueryService.class);
        giftService = new GiftService(
                userQueryService,
                friendQueryService,
                productQueryService,
                preferenceQueryService
        );
    }

    @Test
    @DisplayName("사전 검증 성공 시 수신자·상품·가격·최대 주문 수량을 반환한다")
    void preflight_returnsCalculatedResponse_whenConditionsAreValid() {
        Product product = mock(Product.class);
        Category category = mock(Category.class);
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 2);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(3L);
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getQuantity()).thenReturn(15);
        when(product.getCategory()).thenReturn(category);
        when(category.getId()).thenReturn(9L);
        when(preferenceQueryService.findMatchingWarning(2L, 9L)).thenReturn(Optional.empty());

        GiftPreflightResponse response = giftService.preflight(1L, request);

        assertThat(response.recipient()).isEqualTo(new GiftPreflightResponse.Recipient(2L, "수신자"));
        assertThat(response.product()).isEqualTo(new GiftPreflightResponse.Product(
                3L,
                BigDecimal.valueOf(32_000),
                2,
                BigDecimal.valueOf(64_000),
                10
        ));
        assertThat(response.preferenceWarning()).isNull();

        InOrder order = inOrder(userQueryService, friendQueryService, productQueryService, preferenceQueryService);
        order.verify(userQueryService).findActiveUser(2L);
        order.verify(friendQueryService).areFriends(1L, 2L);
        order.verify(productQueryService).findAvailableProduct(3L);
        order.verify(preferenceQueryService).findMatchingWarning(2L, 9L);
    }

    @Test
    @DisplayName("수신자가 상품 카테고리를 비선호로 등록했으면 경고 정보를 포함한다")
    void preflight_includesPreferenceWarning_whenRecipientDislikesProductCategory() {
        Product product = mock(Product.class);
        Category category = mock(Category.class);
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 2);
        stubValidRecipientAndFriend();
        when(productQueryService.findAvailableProduct(3L)).thenReturn(Optional.of(product));
        when(product.getId()).thenReturn(3L);
        when(product.getPrice()).thenReturn(BigDecimal.valueOf(32_000));
        when(product.getQuantity()).thenReturn(15);
        when(product.getCategory()).thenReturn(category);
        when(category.getId()).thenReturn(9L);
        when(preferenceQueryService.findMatchingWarning(2L, 9L))
                .thenReturn(Optional.of(new PreferenceWarningResult(9L, "패션")));

        GiftPreflightResponse response = giftService.preflight(1L, request);

        assertThat(response.preferenceWarning())
                .isEqualTo(new GiftPreflightResponse.PreferenceWarning(9L, "패션"));
    }

    @Test
    @DisplayName("자기 자신에게 선물하면 GIFT_CANNOT_SEND_TO_SELF가 발생하고 조회하지 않는다")
    void preflight_throwsGiftCannotSendToSelf_whenSendingToSelf() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 1L, 1);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.GIFT_CANNOT_SEND_TO_SELF);

        verifyNoInteractions(userQueryService, friendQueryService, productQueryService, preferenceQueryService);
    }

    @Test
    @DisplayName("활성 수신자가 없으면 RECIPIENT_NOT_FOUND가 발생한다")
    void preflight_throwsRecipientNotFound_whenRecipientIsNotActive() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L)).thenReturn(Optional.empty());

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FOUND);

        verifyNoInteractions(friendQueryService, productQueryService, preferenceQueryService);
    }

    @Test
    @DisplayName("수신자가 친구가 아니면 RECIPIENT_NOT_FRIEND가 발생한다")
    void preflight_throwsRecipientNotFriend_whenRelationshipDoesNotExist() {
        GiftPreflightRequest request = new GiftPreflightRequest(3L, 2L, 1);
        when(userQueryService.findActiveUser(2L))
                .thenReturn(Optional.of(new ActiveUserSummary(2L, "수신자")));
        when(friendQueryService.areFriends(1L, 2L)).thenReturn(false);

        assertGiftError(() -> giftService.preflight(1L, request), ErrorCode.RECIPIENT_NOT_FRIEND);

        verifyNoInteractions(productQueryService, preferenceQueryService);
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

        verifyNoInteractions(preferenceQueryService);
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
