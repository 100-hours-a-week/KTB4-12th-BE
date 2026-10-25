INSERT INTO coupon_inventories (
    user_id,
    multiplier,
    quantity,
    created_at,
    updated_at
)
SELECT
    users.id,
    multipliers.multiplier,
    0,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM users
         CROSS JOIN (
    SELECT 2 AS multiplier
    UNION ALL
    SELECT 3 AS multiplier
) AS multipliers
         LEFT JOIN coupon_inventories AS inventory
                   ON inventory.user_id = users.id
                       AND inventory.multiplier = multipliers.multiplier
WHERE inventory.id IS NULL;
