-- 로그인 세션/Rate Limit 스키마
-- 엔티티 기준: user_sessions, login_ip_rate_limits, login_email_failure_limits
-- 기존 로컬 DB(Hibernate ddl-auto로 생성된 테이블)에서도 그대로 적용할 수 있도록 IF NOT EXISTS 사용

CREATE TABLE IF NOT EXISTS user_sessions (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    refresh_token VARCHAR(64) NOT NULL,
    authenticated_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    deleted_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_user_sessions PRIMARY KEY (id),
    CONSTRAINT uk_user_sessions_refresh_token UNIQUE (refresh_token),
    CONSTRAINT chk_user_sessions_expires_after_auth CHECK (expires_at > authenticated_at),
    CONSTRAINT chk_user_sessions_revoked_after_created CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CONSTRAINT fk_user_sessions_user FOREIGN KEY (user_id) REFERENCES users (id),
    INDEX idx_user_sessions_user (user_id),
    INDEX idx_user_sessions_expires (expires_at),
    INDEX idx_user_sessions_revoked (revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS login_ip_rate_limits (
    identifier_hash VARCHAR(64) NOT NULL,
    available_tokens DECIMAL(5, 3) NOT NULL,
    last_refilled_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_login_ip_rate_limits PRIMARY KEY (identifier_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS login_email_failure_limits (
    identifier_hash VARCHAR(64) NOT NULL,
    failure_count INT NOT NULL,
    window_started_at DATETIME(6) NOT NULL,
    backoff_level INT NOT NULL,
    blocked_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_login_email_failure_limits PRIMARY KEY (identifier_hash)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
