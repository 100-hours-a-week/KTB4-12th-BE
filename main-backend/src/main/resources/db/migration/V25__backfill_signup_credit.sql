INSERT INTO credit_accounts (
    user_id,
    balance,
    created_at,
    updated_at
)
SELECT
    users.id,
    0,
    users.created_at,
    CURRENT_TIMESTAMP(6)
FROM users
         LEFT JOIN credit_accounts
                   ON credit_accounts.user_id = users.id
WHERE credit_accounts.id IS NULL;

UPDATE credit_accounts
    JOIN users
ON users.id = credit_accounts.user_id
    LEFT JOIN credit_transactions
    ON credit_transactions.deduplication_key =
    CONCAT('SIGNUP_REWARD:', users.id)
    SET
        credit_accounts.balance =
        credit_accounts.balance + 500000,
        credit_accounts.updated_at = CURRENT_TIMESTAMP(6)
WHERE credit_transactions.id IS NULL;

INSERT INTO credit_transactions (
    user_id,
    type,
    amount,
    balance_after,
    order_record_id,
    gift_history_id,
    attendance_date,
    deduplication_key,
    created_at
)
SELECT
    credit_accounts.user_id,
    'SIGNUP_REWARD',
    500000,
    credit_accounts.balance,
    NULL,
    NULL,
    NULL,
    CONCAT(
            'SIGNUP_REWARD:',
            credit_accounts.user_id
    ),
    users.created_at
FROM credit_accounts
         JOIN users
              ON users.id = credit_accounts.user_id
         LEFT JOIN credit_transactions
                   ON credit_transactions.deduplication_key =
                      CONCAT(
                              'SIGNUP_REWARD:',
                              credit_accounts.user_id
                      )
WHERE credit_transactions.id IS NULL;
