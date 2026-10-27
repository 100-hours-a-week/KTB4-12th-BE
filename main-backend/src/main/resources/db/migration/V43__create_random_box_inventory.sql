ALTER TABLE roulette_spins
    DROP CHECK chk_roulette_spins_segment,
    ADD CONSTRAINT chk_roulette_spins_segment CHECK (
        (segment_id IN (0, 3) AND reward_type = 'COUPON' AND multiplier = 2)
        OR (segment_id = 1 AND reward_type = 'COUPON' AND multiplier = 3)
        OR (segment_id IN (2, 4, 5) AND reward_type = 'MISS')
    );

CREATE TABLE random_box_campaigns (
    id INT NOT NULL PRIMARY KEY,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT chk_random_box_campaign CHECK (id = 1)
);

CREATE TABLE random_box_prizes (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    voucher_code VARCHAR(1000) NOT NULL,
    voucher_hash VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    CONSTRAINT uk_random_box_voucher_hash UNIQUE (voucher_hash),
    CONSTRAINT chk_random_box_prize_name CHECK (CHAR_LENGTH(TRIM(name)) > 0),
    CONSTRAINT chk_random_box_voucher_code CHECK (CHAR_LENGTH(TRIM(voucher_code)) > 0)
);

CREATE TABLE random_box_tickets (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    campaign_id INT NOT NULL,
    slot_number INT NOT NULL,
    prize_id BIGINT NULL,
    assigned_user_id BIGINT NULL,
    assigned_at DATETIME(6) NULL,
    opened_at DATETIME(6) NULL,
    CONSTRAINT uk_random_box_slot UNIQUE (campaign_id, slot_number),
    CONSTRAINT uk_random_box_prize UNIQUE (prize_id),
    CONSTRAINT fk_random_box_campaign FOREIGN KEY (campaign_id) REFERENCES random_box_campaigns(id),
    CONSTRAINT fk_random_box_prize FOREIGN KEY (prize_id) REFERENCES random_box_prizes(id),
    CONSTRAINT fk_random_box_owner FOREIGN KEY (assigned_user_id) REFERENCES users(id),
    CONSTRAINT chk_random_box_slot CHECK (slot_number BETWEEN 1 AND 100),
    CONSTRAINT chk_random_box_assignment CHECK (
        (assigned_user_id IS NULL AND assigned_at IS NULL AND opened_at IS NULL)
        OR (assigned_user_id IS NOT NULL AND assigned_at IS NOT NULL)
    ),
    INDEX idx_random_box_available (campaign_id, assigned_user_id, slot_number),
    INDEX idx_random_box_owner (assigned_user_id, assigned_at DESC, id DESC)
);
