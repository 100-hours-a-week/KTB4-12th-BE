ALTER TABLE profiling_jobs
    ADD COLUMN result_fingerprint CHAR(64) NULL
        COMMENT 'BE 최종 결과 비교용 SHA-256',
    ADD CONSTRAINT chk_profiling_jobs_result_fingerprint
        CHECK (
            result_fingerprint IS NULL
            OR REGEXP_LIKE(
                result_fingerprint,
                '^[0-9a-f]{64}$',
                'c'
            )
        );
