CREATE TABLE credit_accounts (
                                 id BIGINT NOT NULL AUTO_INCREMENT,
                                 user_id BIGINT NOT NULL,
                                 balance BIGINT NOT NULL DEFAULT 0,
                                 created_at DATETIME(6) NOT NULL,
                                 updated_at DATETIME(6) NOT NULL,
                                 CONSTRAINT pk_credit_accounts PRIMARY KEY (id),
                                 CONSTRAINT uk_credit_accounts_user UNIQUE (user_id),
                                 CONSTRAINT chk_credit_accounts_balance_non_negative
                                     CHECK (balance >= 0),
                                 CONSTRAINT fk_credit_accounts_user
                                     FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE credit_transactions (
                                     id BIGINT NOT NULL AUTO_INCREMENT,
                                     user_id BIGINT NOT NULL,
                                     type VARCHAR(32) NOT NULL,
                                     amount BIGINT NOT NULL,
                                     balance_after BIGINT NOT NULL,
                                     order_record_id BIGINT NULL,
                                     gift_history_id BIGINT NULL,
                                     attendance_date DATE NULL,
                                     deduplication_key VARCHAR(120) NOT NULL,
                                     created_at DATETIME(6) NOT NULL,
                                     CONSTRAINT pk_credit_transactions PRIMARY KEY (id),
                                     CONSTRAINT uk_credit_transactions_deduplication
                                         UNIQUE (deduplication_key),
                                     CONSTRAINT chk_credit_transactions_type
                                         CHECK (
                                             type IN (
                                                      'SIGNUP_REWARD',
                                                      'FRIENDSHIP_REWARD',
                                                      'DAILY_ATTENDANCE_REWARD',
                                                      'PAYMENT_USE',
                                                      'PAYMENT_REFUND',
                                                      'GIFT_COMPLETED_REWARD'
                                                 )
                                             ),
                                     CONSTRAINT chk_credit_transactions_amount_positive
                                         CHECK (amount > 0),
                                     CONSTRAINT chk_credit_transactions_balance_non_negative
                                         CHECK (balance_after >= 0),
                                     CONSTRAINT fk_credit_transactions_user
                                         FOREIGN KEY (user_id) REFERENCES users (id),
                                     CONSTRAINT fk_credit_transactions_gift_history
                                         FOREIGN KEY (gift_history_id) REFERENCES gift_histories (id),
                                     INDEX idx_credit_transactions_user_created (
                                                                                 user_id,
                                                                                 created_at,
                                                                                 id
                                         ),
                                     INDEX idx_credit_transactions_order_record (order_record_id),
                                     INDEX idx_credit_transactions_gift_history (gift_history_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;