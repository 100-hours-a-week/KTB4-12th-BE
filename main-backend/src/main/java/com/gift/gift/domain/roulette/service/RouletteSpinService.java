package com.gift.gift.domain.roulette.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.coupon.service.CouponIssueService;
import com.gift.gift.domain.roulette.dto.response.RouletteSpinResponse;
import com.gift.gift.domain.roulette.entity.RouletteRewardType;
import com.gift.gift.domain.roulette.entity.RouletteSpin;
import com.gift.gift.domain.roulette.repository.RouletteAccountRepository;
import com.gift.gift.domain.roulette.repository.RouletteSpinRepository;
import com.gift.gift.domain.roulette.support.RouletteDay;
import com.gift.gift.domain.roulette.support.RouletteDraw;
import com.gift.gift.domain.roulette.support.RoulettePolicy;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

@Service
@RequiredArgsConstructor
public class RouletteSpinService {

    private final RouletteAccountRepository accountRepository;
    private final RouletteSpinRepository spinRepository;
    private final RouletteService rouletteService;
    private final CouponIssueService couponIssueService;
    private final RouletteDraw draw;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RouletteSpinResponse spin(Long userId, String idempotencyKey) {
        UUID key = canonicalKey(idempotencyKey);
        accountRepository.insertIfAbsent(userId);
        accountRepository.findByUserIdForUpdate(userId)
                .orElseThrow(() -> new IllegalStateException("룰렛 계정을 찾을 수 없습니다."));

        RouletteSpin existing = spinRepository.findByUserIdAndIdempotencyKey(userId, key).orElse(null);
        if (existing != null) {
            return RouletteSpinResponse.from(existing);
        }

        RouletteDay day = rouletteService.consumeTicket(userId);
        RoulettePolicy.Segment segment = draw.draw();
        RouletteSpin spin = spinRepository.saveAndFlush(new RouletteSpin(
                userId, key, day.date(), segment.segmentId(), segment.rewardType(), segment.multiplier(),
                RoulettePolicy.VERSION, day.remaining(), LocalDateTime.ofInstant(clock.instant(), RoulettePolicy.ZONE)
        ));
        if (segment.rewardType() == RouletteRewardType.COUPON) {
            couponIssueService.issue(userId, segment.multiplier());
        }
        return RouletteSpinResponse.from(spin);
    }

    private UUID canonicalKey(String value) {
        try {
            UUID key = UUID.fromString(value == null ? "" : value);
            if (!key.toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "Idempotency-Key는 UUID 형식이어야 합니다.");
        }
    }
}
