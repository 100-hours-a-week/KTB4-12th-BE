package com.gift.gift.domain.randombox.support;

import java.security.SecureRandom;

import org.springframework.stereotype.Component;

@Component
public class RandomBoxDraw {
    private final SecureRandom random = new SecureRandom();

    public RandomBoxOutcome draw() {
        return RandomBoxOutcome.select(random.nextInt(RandomBoxOutcome.totalWeight()));
    }
}
