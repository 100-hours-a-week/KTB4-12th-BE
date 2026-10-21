package com.gift.gift.domain.order.service;

import java.math.BigDecimal;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentValidator {

    private final ProductQueryService productQueryService;
    private final UserQueryService userQueryService;
    private final FriendQueryService friendQueryService;
    private final CreditAccountRepository creditAccountRepository;

    public PaymentValidation validate(Long payerId, PaymentCreateRequest request) {

        Product product = productQueryService
                .findAvailableProduct(request.productId())
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.PRODUCT_NOT_FOUND)
                );

        ActiveUserSummary recipient = userQueryService
                .findActiveUser(request.recipientUserId())
                .orElseThrow(
                        () -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND)
                );

        if (payerId.equals(recipient.userId())) {
            throw new BusinessException(ErrorCode.GIFT_CANNOT_SEND_TO_SELF);
        }

        if (!friendQueryService.areFriends(payerId, recipient.userId())) {
            throw new BusinessException(ErrorCode.RECIPIENT_NOT_FRIEND);
        }

        BigDecimal currentPrice = product.getPrice();

        if (currentPrice.compareTo(BigDecimal.valueOf(request.expectedUnitPrice())) != 0) {
            throw new BusinessException(ErrorCode.GIFT_CONDITIONS_CHANGED);
        }

        if (product.getQuantity() < request.quantity()) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_STOCK);
        }

        long unitPrice = toLong(currentPrice);
        long amount = toLong(
                currentPrice.multiply(BigDecimal.valueOf(request.quantity()))
        );

        CreditAccount account = creditAccountRepository
                .findByUserId(payerId)
                .orElseThrow(
                        () -> new IllegalStateException("결제자의 크레딧 계좌가 존재하지 않습니다.")
                );

        if (account.getBalance() < amount) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_CREDIT);
        }

        return new PaymentValidation(unitPrice, amount);
    }

    private long toLong(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalStateException("상품 가격을 정수 크레딧으로 변환할 수 없습니다.", exception);
        }
    }
}
