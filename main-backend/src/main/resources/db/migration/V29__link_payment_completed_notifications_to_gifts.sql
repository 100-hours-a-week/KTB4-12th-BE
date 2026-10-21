UPDATE notifications n
JOIN gift_histories g ON g.order_record_id = n.reference_id
SET n.reference_type = 'GIFT',
    n.reference_id = g.id
WHERE n.type = 'PAYMENT_COMPLETED'
  AND n.reference_type = 'PAYMENT';
