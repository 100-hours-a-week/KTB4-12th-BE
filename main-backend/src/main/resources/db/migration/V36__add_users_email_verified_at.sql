ALTER TABLE users
    ADD COLUMN email_verified_at DATETIME(6) NULL AFTER email;
