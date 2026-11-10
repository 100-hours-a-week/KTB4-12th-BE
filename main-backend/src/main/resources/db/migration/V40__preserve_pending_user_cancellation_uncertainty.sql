-- 기존 요청은 이미 전송됐을 수 있으므로 신규 전송 대상으로 선점하지 않는다.
UPDATE payment_cancellations
SET failure_code = 'CANCELLATION_RESULT_UNCONFIRMED',
    updated_at = CURRENT_TIMESTAMP(6)
WHERE cancellation_source = 'USER'
  AND status = 'PENDING'
  AND failure_code IS NULL;
