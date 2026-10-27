package com.gift.gift.domain.roulette.support;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class RouletteDraw {

    private final SecureRandom random = new SecureRandom();

    public RoulettePolicy.Segment draw() {
        return RoulettePolicy.select(random.nextInt(RoulettePolicy.TOTAL_WEIGHT));
    }
}
