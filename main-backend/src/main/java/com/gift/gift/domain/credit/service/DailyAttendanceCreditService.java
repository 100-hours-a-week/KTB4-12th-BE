package com.gift.gift.domain.credit.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.roulette.service.RouletteService;

@Service
@RequiredArgsConstructor
public class DailyAttendanceCreditService {

    private final RouletteService rouletteService;

    @Transactional
    public void grant(Long userId) {
        rouletteService.attend(userId);
    }
}
