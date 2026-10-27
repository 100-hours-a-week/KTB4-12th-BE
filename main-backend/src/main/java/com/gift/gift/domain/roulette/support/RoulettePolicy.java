package com.gift.gift.domain.roulette.support;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.List;

import com.gift.gift.domain.roulette.entity.RouletteRewardType;

public final class RoulettePolicy {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    public static final String VERSION = "coupon-v3";
    public static final int TOTAL_WEIGHT = 10_000;
    public static final List<Segment> SEGMENTS = List.of(
            segment(0, RouletteRewardType.COUPON, 2, 1750),
            segment(1, RouletteRewardType.COUPON, 3, 1500),
            segment(2, RouletteRewardType.MISS, null, 1667),
            segment(3, RouletteRewardType.COUPON, 2, 1750),
            segment(4, RouletteRewardType.MISS, null, 1667),
            segment(5, RouletteRewardType.MISS, null, 1666)
    );

    private RoulettePolicy() {
    }

    public static Segment select(int value) {
        if (value < 0 || value >= TOTAL_WEIGHT) {
            throw new IllegalArgumentException("추첨 값은 0 이상 10000 미만이어야 합니다.");
        }
        int cumulative = 0;
        for (Segment segment : SEGMENTS) {
            cumulative += segment.probability().movePointRight(2).intValueExact();
            if (value < cumulative) {
                return segment;
            }
        }
        throw new IllegalStateException("룰렛 확률의 합이 100이 아닙니다.");
    }

    private static Segment segment(int id, RouletteRewardType type, Integer multiplier, int basisPoints) {
        return new Segment(id, type, multiplier, BigDecimal.valueOf(basisPoints, 2));
    }

    public record Segment(int segmentId, RouletteRewardType rewardType, Integer multiplier, BigDecimal probability) {
    }
}
