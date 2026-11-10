CREATE TABLE roulette_accounts (
    user_id BIGINT NOT NULL PRIMARY KEY,
    available_tickets BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT chk_roulette_accounts_balance CHECK (available_tickets >= 0),
    CONSTRAINT fk_roulette_accounts_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE roulette_days (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    participation_date DATE NOT NULL,
    attendance_granted INT NOT NULL DEFAULT 0,
    gift_granted INT NOT NULL DEFAULT 0,
    used INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_roulette_days_user_date UNIQUE (user_id, participation_date),
    CONSTRAINT fk_roulette_days_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT chk_roulette_days_attendance CHECK (attendance_granted BETWEEN 0 AND 1),
    CONSTRAINT chk_roulette_days_gift CHECK (gift_granted >= 0),
    CONSTRAINT chk_roulette_days_used CHECK (used BETWEEN 0 AND 1)
);

CREATE TABLE roulette_ticket_grants (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    participation_date DATE NOT NULL,
    source_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_type VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_roulette_grants_source UNIQUE (source_key),
    CONSTRAINT fk_roulette_grants_day FOREIGN KEY (user_id, participation_date)
        REFERENCES roulette_days(user_id, participation_date),
    CONSTRAINT chk_roulette_grants_source CHECK (source_type IN ('ATTENDANCE', 'GIFT'))
);
