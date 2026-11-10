package com.gift.gift.domain.roulette.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.gift.gift.domain.roulette.support.RouletteDay;
import com.gift.gift.domain.roulette.support.RoulettePolicy;

public record RouletteAttendanceResponse(
        boolean granted,
        LocalDate date,
        OffsetDateTime nextResetAt,
        int attendanceGranted,
        int giftGranted,
        int used,
        long remaining
) {

    public static RouletteAttendanceResponse from(boolean granted, RouletteDay day) {
        return new RouletteAttendanceResponse(
                granted, day.date(), day.date().plusDays(1).atStartOfDay(RoulettePolicy.ZONE).toOffsetDateTime(),
                day.attendanceGranted(), day.giftGranted(), day.used(), day.remaining()
        );
    }
}
