package com.gift.gift.domain.roulette.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record RouletteSettings(@Value("${app.roulette.enabled:false}") boolean enabled) {
}
