CREATE TABLE profiling_jobs
(
    request_id         VARCHAR(36) NOT NULL COMMENT '요청 ID',
    profile_id         BIGINT      NOT NULL COMMENT '프로파일 ID',
    source_version     BIGINT      NOT NULL COMMENT '요청 버전',
    request_body       LONGTEXT    NOT NULL COMMENT '요청 본문 스냅샷',

    publish_status     VARCHAR(20) NOT NULL DEFAULT 'READY' COMMENT '메시지 발행 상태',
    attempt_count      INT         NOT NULL DEFAULT 0 COMMENT '발행 시도 횟수',
    first_failed_at    DATETIME(6) NULL COMMENT '최초 발행 실패 시각',
    next_attempt_at    DATETIME(6) NULL COMMENT '다음 발행 시도 시각',
    claim_token        VARCHAR(36) NULL COMMENT '발행 선점 토큰',
    claimed_at         DATETIME(6) NULL COMMENT '발행 선점 시각',
    published_at       DATETIME(6) NULL COMMENT '발행 확인 시각',

    result_status      VARCHAR(20) NOT NULL DEFAULT 'WAITING' COMMENT '작업 결과 상태',
    finished_at        DATETIME(6) NULL COMMENT '작업 종료 시각',
    last_error_code    VARCHAR(100) NULL COMMENT '마지막 오류 코드',
    last_error_message TEXT NULL COMMENT '마지막 오류 메시지',

    created_at         DATETIME(6) NOT NULL COMMENT '생성 일시',
    updated_at         DATETIME(6) NOT NULL COMMENT '수정 일시',

    CONSTRAINT pk_profiling_jobs
        PRIMARY KEY (request_id),

    CONSTRAINT uk_profiling_jobs_profile_version
        UNIQUE (profile_id, source_version),

    CONSTRAINT fk_profiling_jobs_profile
        FOREIGN KEY (profile_id)
            REFERENCES recipient_profiles (id),

    CONSTRAINT chk_profiling_jobs_version
        CHECK (source_version >= 1),

    CONSTRAINT chk_profiling_jobs_attempt_count
        CHECK (attempt_count >= 0),

    CONSTRAINT chk_profiling_jobs_publish_status
        CHECK (
            publish_status IN (
                               'READY', 'CLAIMED', 'RETRY_WAIT', 'PUBLISHED', 'BLOCKED'
                )
            ),

    CONSTRAINT chk_profiling_jobs_result_status
        CHECK (
            result_status IN (
                              'WAITING', 'COMPLETED', 'FAILED', 'SUPERSEDED'
                )
            ),

    CONSTRAINT chk_profiling_jobs_claim
        CHECK (
            (claim_token IS NULL AND claimed_at IS NULL)
                OR
            (claim_token IS NOT NULL AND claimed_at IS NOT NULL)
            ),

    INDEX              idx_profiling_jobs_publish (publish_status, next_attempt_at, created_at),

    INDEX              idx_profiling_jobs_claim (publish_status, claimed_at),

    INDEX              idx_profiling_jobs_cleanup (result_status, finished_at)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '프로파일링 작업';
