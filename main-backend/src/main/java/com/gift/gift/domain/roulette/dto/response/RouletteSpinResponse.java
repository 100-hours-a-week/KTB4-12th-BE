package com.gift.gift.domain.roulette.dto.response;

import java.time.LocalDate;

import com.gift.gift.domain.roulette.entity.RouletteRewardType;
import com.gift.gift.domain.roulette.entity.RouletteSpin;

public record RouletteSpinResponse(
        long spinId,
        LocalDate date,
        int segmentId,
        RouletteRewardType rewardType,
        Integer multiplier,
        String rewardVersion,
        long remaining
) {

    public static RouletteSpinResponse from(RouletteSpin spin) {
        return new RouletteSpinResponse(spin.getId(), spin.getParticipationDate(), spin.getSegmentId(),
                spin.getRewardType(), spin.getMultiplier(), spin.getRewardVersion(), spin.getRemainingTickets());
    }
}
