package com.gift.gift.domain.randombox.support;

public enum RandomBoxOutcome {
    PRIZE(15),
    MISS(85);

    private final int weight;
    private static final int TOTAL_WEIGHT;

    static {
        int total = 0;
        for (RandomBoxOutcome outcome : values()) {
            total = Math.addExact(total, outcome.weight);
        }
        if (total == 0) {
            throw new IllegalStateException("랜덤박스 가중치 합은 양수여야 합니다.");
        }
        TOTAL_WEIGHT = total;
    }

    RandomBoxOutcome(int weight) {
        if (weight < 0) {
            throw new IllegalArgumentException("랜덤박스 가중치는 음수일 수 없습니다.");
        }
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    public static int totalWeight() {
        return TOTAL_WEIGHT;
    }

    public static RandomBoxOutcome select(int value) {
        if (value < 0 || value >= TOTAL_WEIGHT) {
            throw new IllegalArgumentException("추첨 값은 0 이상 가중치 합 미만이어야 합니다.");
        }
        int cumulative = 0;
        for (RandomBoxOutcome outcome : values()) {
            cumulative += outcome.weight;
            if (value < cumulative) {
                return outcome;
            }
        }
        throw new IllegalStateException("랜덤박스 추첨 결과를 결정할 수 없습니다.");
    }
}
