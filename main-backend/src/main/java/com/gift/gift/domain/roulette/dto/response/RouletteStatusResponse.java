package com.gift.gift.domain.roulette.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import com.gift.gift.domain.roulette.support.RouletteDay;
import com.gift.gift.domain.roulette.support.RoulettePolicy;

public record RouletteStatusResponse(
        LocalDate date,
        Instant serverTime,
        OffsetDateTime nextResetAt,
        int attendanceGranted,
        int giftGranted,
        int used,
        long remaining,
        int dailyRemaining,
        String rewardVersion,
        List<RoulettePolicy.Segment> segments
) {

    public static RouletteStatusResponse from(RouletteDay day, Instant now) {
        return new RouletteStatusResponse(
                day.date(), now, day.date().plusDays(1).atStartOfDay(RoulettePolicy.ZONE).toOffsetDateTime(),
                day.attendanceGranted(), day.giftGranted(), day.used(), day.remaining(),
                day.dailyRemaining(), RoulettePolicy.VERSION, RoulettePolicy.SEGMENTS
        );
    }
}
