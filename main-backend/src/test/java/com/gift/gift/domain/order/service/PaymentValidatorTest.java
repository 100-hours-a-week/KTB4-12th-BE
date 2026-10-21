package com.gift.gift.domain.order.service;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.friend.service.FriendQueryService;
import com.gift.gift.domain.order.dto.request.PaymentCreateRequest;
import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.domain.product.service.ProductQueryService;
import com.gift.gift.domain.user.service.UserQueryService;
import com.gift.gift.domain.user.support.ActiveUserSummary;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentValidatorTest {

    private static final Long PAYER_ID = 1L;
    private static final Long RECIPIENT_ID = 2L;
    private static final Long PRODUCT_ID = 101L;

    private ProductQueryService productQueryService;
    private UserQueryService userQueryService;
    private FriendQueryService friendQueryService;
    private CreditAccountRepository creditAccountRepository;
    private PaymentValidator validator;

    @BeforeEach
    void setUp() {
        productQueryService = mock(ProductQueryService.class);
        userQueryService = mock(UserQueryService.class);
        friendQueryService = mock(FriendQueryService.class);
        creditAccountRepository = mock(CreditAccountRepository.class);

        validator = new PaymentValidator(
                productQueryService,
                userQueryService,
                friendQueryService,
                creditAccountRepository
        );
    }

    @Test
    @DisplayName("결제 조건이 유효하면 서버 단가와 총액을 반환한다")
    void validate_returnsPriceAndAmount() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                2,
                32_000L
        );
        stubValidContext(request, 100_000L);

        PaymentValidation result = validator.validate(PAYER_ID, request);

        assertThat(result.unitPrice()).isEqualTo(32_000L);
        assertThat(result.amount()).isEqualTo(64_000L);
    }

    @Test
    @DisplayName("상품이 없으면 PRODUCT_NOT_FOUND를 발생시킨다")
    void validate_rejectsMissingProduct() {
        PaymentCreateRequest request = request(RECIPIENT_ID, 2, 32_000L);

        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.empty());

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.PRODUCT_NOT_FOUND
        );
    }

    @Test
    @DisplayName("수신자가 없으면 RECIPIENT_NOT_FOUND를 발생시킨다")
    void validate_rejectsMissingRecipient() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                2,
                32_000L
        );
        Product product = product(32_000L, 5);

        when(productQueryService.findAvailableProduct(PRODUCT_ID)).thenReturn(Optional.of(product));
        when(userQueryService.findActiveUser(RECIPIENT_ID)).thenReturn(Optional.empty());

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.RECIPIENT_NOT_FOUND
        );
    }

    @Test
    @DisplayName("자기 자신에게 결제 선물을 보낼 수 없다")
    void validate_rejectsSelfGift() {
        PaymentCreateRequest request = request(PAYER_ID, 1, 32_000L);
        Product product = product(32_000L, 5);

        when(productQueryService.findAvailableProduct(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(userQueryService.findActiveUser(PAYER_ID))
                .thenReturn(Optional.of(
                        new ActiveUserSummary(PAYER_ID, "결제자")
                ));

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.GIFT_CANNOT_SEND_TO_SELF
        );
    }

    @Test
    @DisplayName("친구가 아닌 수신자에게 결제 선물을 보낼 수 없다")
    void validate_rejectsNonFriend() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                1,
                32_000L
        );
        Product product = product(32_000L, 5);

        when(productQueryService.findAvailableProduct(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(userQueryService.findActiveUser(RECIPIENT_ID))
                .thenReturn(Optional.of(
                        new ActiveUserSummary(RECIPIENT_ID, "수신자")
                ));
        when(friendQueryService.areFriends(PAYER_ID, RECIPIENT_ID))
                .thenReturn(false);

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.RECIPIENT_NOT_FRIEND
        );
    }

    @Test
    @DisplayName("확인한 가격과 현재 가격이 다르면 결제를 거절한다")
    void validate_rejectsChangedPrice() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                1,
                31_000L
        );
        Product product = product(32_000L, 5);

        stubRecipientAndFriend(product);

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.GIFT_CONDITIONS_CHANGED
        );
    }

    @Test
    @DisplayName("재고가 부족하면 결제를 거절한다")
    void validate_rejectsInsufficientStock() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                2,
                32_000L
        );
        Product product = product(32_000L, 1);

        stubRecipientAndFriend(product);

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.INSUFFICIENT_STOCK
        );
    }

    @Test
    @DisplayName("크레딧 잔액이 부족하면 결제를 거절한다")
    void validate_rejectsInsufficientCredit() {
        PaymentCreateRequest request = request(
                RECIPIENT_ID,
                2,
                32_000L
        );
        Product product = product(32_000L, 5);

        stubRecipientAndFriend(product);
        when(creditAccountRepository.findByUserId(PAYER_ID))
                .thenReturn(Optional.of(
                        new CreditAccount(PAYER_ID, 63_999L)
                ));

        assertError(
                () -> validator.validate(PAYER_ID, request),
                ErrorCode.INSUFFICIENT_CREDIT
        );
    }

    private void stubValidContext(
            PaymentCreateRequest request,
            long balance
    ) {
        Product product = product(
                request.expectedUnitPrice(),
                5
        );

        stubRecipientAndFriend(product);

        when(creditAccountRepository.findByUserId(PAYER_ID))
                .thenReturn(Optional.of(
                        new CreditAccount(PAYER_ID, balance)
                ));
    }

    private void stubRecipientAndFriend(Product product) {
        when(productQueryService.findAvailableProduct(PRODUCT_ID))
                .thenReturn(Optional.of(product));
        when(userQueryService.findActiveUser(RECIPIENT_ID))
                .thenReturn(Optional.of(
                        new ActiveUserSummary(RECIPIENT_ID, "수신자")
                ));
        when(friendQueryService.areFriends(PAYER_ID, RECIPIENT_ID))
                .thenReturn(true);
    }

    private Product product(long price, int stock) {
        Product product = mock(Product.class);

        when(product.getPrice())
                .thenReturn(BigDecimal.valueOf(price));
        when(product.getQuantity())
                .thenReturn(stock);

        return product;
    }

    private PaymentCreateRequest request(
            Long recipientId,
            int quantity,
            long expectedUnitPrice
    ) {
        return new PaymentCreateRequest(
                PRODUCT_ID,
                recipientId,
                quantity,
                expectedUnitPrice
        );
    }

    private void assertError(
            Runnable action,
            ErrorCode errorCode
    ) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        exception -> assertThat(
                                exception.getErrorCode()
                        ).isEqualTo(errorCode)
                );
    }
}
