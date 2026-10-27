CREATE TABLE roulette_spins (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    idempotency_key BINARY(16) NOT NULL,
    participation_date DATE NOT NULL,
    segment_id INT NOT NULL,
    reward_type VARCHAR(20) NOT NULL,
    multiplier INT NULL,
    reward_version VARCHAR(40) NOT NULL,
    remaining_tickets BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_roulette_spins_user_key UNIQUE (user_id, idempotency_key),
    CONSTRAINT fk_roulette_spins_day FOREIGN KEY (user_id, participation_date)
        REFERENCES roulette_days(user_id, participation_date),
    CONSTRAINT chk_roulette_spins_balance CHECK (remaining_tickets >= 0),
    CONSTRAINT chk_roulette_spins_reward CHECK (
        (reward_type = 'COUPON' AND multiplier IS NOT NULL AND multiplier IN (2, 3))
        OR (reward_type = 'MISS' AND multiplier IS NULL)
    ),
    CONSTRAINT chk_roulette_spins_segment CHECK (
        (segment_id IN (0, 3) AND reward_type = 'COUPON' AND multiplier = 2)
        OR (segment_id = 1 AND reward_type = 'COUPON' AND multiplier = 3)
        OR (segment_id IN (2, 4) AND reward_type = 'MISS')
    )
);
