ALTER TABLE credit_transactions
    DROP CHECK chk_credit_transactions_type;

ALTER TABLE credit_transactions
    ADD CONSTRAINT chk_credit_transactions_type
        CHECK (
            type IN (
                'SIGNUP_REWARD',
                'FRIENDSHIP_REWARD',
                'DAILY_ATTENDANCE_REWARD',
                'PAYMENT_USE',
                'PAYMENT_REFUND',
                'GIFT_COMPLETED_REWARD',
                'DISLIKE_CATEGORY_UPDATE_USE',
                'GIFT_PREFERENCE_UPDATE_USE'
            )
        );

CREATE TABLE ai_chat_turn_usages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    turn_id BINARY(16) NOT NULL,
    usage_date DATE NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_ai_chat_turn_usages PRIMARY KEY (id),
    CONSTRAINT uk_ai_chat_turn_usages_user_conversation_turn
        UNIQUE (user_id, conversation_id, turn_id),
    CONSTRAINT fk_ai_chat_turn_usages_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_ai_chat_turn_usages_user_date (user_id, usage_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
