package db.migration;

import java.sql.ResultSet;
import java.sql.Statement;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V51__remove_legacy_profile_dispatch_fields extends BaseJavaMigration {
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        try (Statement statement = context.getConnection().createStatement()) {
            // 기존 발송 인스턴스를 모두 중지하고 진행 요청을 정리한 뒤 컬럼을 제거한다.
            try (ResultSet rows = statement.executeQuery("""
                    SELECT COUNT(*) FROM recipient_profiles p
                    WHERE p.dispatch_claim_token IS NOT NULL
                       OR p.dispatch_claimed_at IS NOT NULL
                       OR p.dispatch_claim_last_changed_at IS NOT NULL
                       OR (p.profile_status = 'PENDING' AND NOT EXISTS (
                           SELECT 1 FROM profiling_jobs j
                           WHERE j.profile_id = p.id AND j.source_version = p.source_version
                       ))
                    """)) {
                rows.next();
                if (rows.getLong(1) != 0) {
                    throw new IllegalStateException(
                            "Legacy HTTP profiling requests remain; resolve claims/PENDING before V51.");
                }
            }
            statement.execute("""
                    ALTER TABLE recipient_profiles
                        DROP CHECK chk_recipient_profiles_retry_count,
                        DROP CHECK chk_recipient_profiles_dispatch_claim,
                        DROP INDEX idx_recipient_profiles_pending_timeout,
                        DROP COLUMN retry_count,
                        DROP COLUMN dispatch_claim_token,
                        DROP COLUMN dispatch_claimed_at,
                        DROP COLUMN dispatch_claim_last_changed_at
                    """);
        }
    }
}
