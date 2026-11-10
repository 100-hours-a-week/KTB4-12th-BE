package com.gift.gift.domain.roulette.support;

import java.time.LocalDate;

public record RouletteDay(LocalDate date, int attendanceGranted, int giftGranted, int used, long remaining) {
}
