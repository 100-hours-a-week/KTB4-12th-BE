ALTER TABLE roulette_days
    DROP CHECK chk_roulette_days_used,
    ADD CONSTRAINT chk_roulette_days_used CHECK (used >= 0);
