-- 기존 프로필의 분석 상태는 보존하고, 프로필이 없는 사용자만 유효한 초기 상태로 보충한다.
-- 변경 시각을 NULL로 두어 보충 작업 자체가 AI 발송을 예약하지 않도록 한다.
INSERT INTO recipient_profiles (
    recipient_id,
    profile_status,
    source_version,
    analyzed_source_version,
    last_changed_at,
    window_started_at,
    pending_since,
    retry_count,
    dispatch_claim_token,
    dispatch_claimed_at,
    dispatch_claim_last_changed_at,
    created_at,
    updated_at
)
SELECT
    users.id,
    'NONE',
    0,
    0,
    NULL,
    NULL,
    NULL,
    0,
    NULL,
    NULL,
    NULL,
    CURRENT_TIMESTAMP(6),
    CURRENT_TIMESTAMP(6)
FROM users
    LEFT JOIN recipient_profiles
        ON recipient_profiles.recipient_id = users.id
WHERE recipient_profiles.id IS NULL;
