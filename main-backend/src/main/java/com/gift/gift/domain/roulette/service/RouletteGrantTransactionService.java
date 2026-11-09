package com.gift.gift.domain.roulette.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.repository.GiftHistoryRepository;
import com.gift.gift.domain.order.entity.Order;
import com.gift.gift.domain.order.repository.OrderRepository;
import com.gift.gift.domain.roulette.support.RoulettePolicy;

@Service
@RequiredArgsConstructor
public class RouletteGrantTransactionService {

    private final OrderRepository orderRepository;
    private final GiftHistoryRepository giftHistoryRepository;
    private final RouletteService rouletteService;

    @Transactional
    public void grantForCompletedPayment(long orderRecordId, LocalDateTime paymentCompletedAt) {
        if (orderRecordId <= 0 || paymentCompletedAt == null) {
            throw new IllegalArgumentException("결제 완료 주문 ID와 시각을 확인해 주세요.");
        }

        Order order = orderRepository.findById(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("결제 완료 주문이 존재하지 않습니다."));

        GiftHistory gift = giftHistoryRepository.findByOrderRecordId(orderRecordId)
                .orElseThrow(() -> new IllegalStateException("결제 완료 주문의 선물 이력이 존재하지 않습니다."));

        Long payerId = order.getPayerId();
        if (payerId == null
                || gift.getSender() == null
                || gift.getSender().getId() == null
                || !payerId.equals(gift.getSender().getId())) {
            throw new IllegalStateException("주문 결제자와 선물 발신자가 일치하지 않습니다.");
        }

        LocalDate participationDate = paymentCompletedAt.atZone(RoulettePolicy.ZONE).toLocalDate();
        rouletteService.grantGift(payerId, gift.getId(), participationDate);
    }
}
