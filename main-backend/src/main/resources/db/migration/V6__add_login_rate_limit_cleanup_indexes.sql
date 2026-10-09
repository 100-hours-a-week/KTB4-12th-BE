-- 만료 세션·로그인 제한 배치 정리 시 정리 대상 조회를 위한 인덱스를 추가한다.
CREATE INDEX idx_login_ip_rate_limits_updated_at
    ON login_ip_rate_limits (updated_at);

CREATE INDEX idx_login_email_failure_limits_cleanup
    ON login_email_failure_limits (updated_at, blocked_until);
