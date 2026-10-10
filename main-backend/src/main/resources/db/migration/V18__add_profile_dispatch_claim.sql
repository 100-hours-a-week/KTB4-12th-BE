ALTER TABLE recipient_profiles
    ADD COLUMN dispatch_claim_token VARCHAR(36) NULL,
    ADD COLUMN dispatch_claimed_at DATETIME(6) NULL,
    ADD COLUMN dispatch_claim_last_changed_at DATETIME(6) NULL,
    ADD CONSTRAINT chk_recipient_profiles_dispatch_claim
        CHECK (
            (
                dispatch_claim_token IS NULL
                AND dispatch_claimed_at IS NULL
                AND dispatch_claim_last_changed_at IS NULL
            )
            OR
            (
                dispatch_claim_token IS NOT NULL
                AND dispatch_claimed_at IS NOT NULL
            )
        );
