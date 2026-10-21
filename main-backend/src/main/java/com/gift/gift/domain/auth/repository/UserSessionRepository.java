package com.gift.gift.domain.auth.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.auth.entity.UserSession;

public interface UserSessionRepository
        extends JpaRepository<UserSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(
                    name = "jakarta.persistence.lock.timeout",
                    value = "3000"
            ),
            @QueryHint(
                    name = "jakarta.persistence.query.timeout",
                    value = "3000"
            )
    })
    @Query("""
            select session
            from UserSession session
            where session.refreshTokenHash = :refreshTokenHash
            """)
    Optional<UserSession> findByRefreshTokenHashForUpdate(
            @Param("refreshTokenHash")
            String refreshTokenHash
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                    delete from user_sessions
                    where (
                        expires_at <= :inactiveBefore
                        or (
                            revoked_at is not null
                            and revoked_at <= :inactiveBefore
                        )
                    )
                    order by id
                    limit :batchSize
                    """,
            nativeQuery = true
    )
    int deleteInactiveBatch(
            @Param("inactiveBefore")
            LocalDateTime inactiveBefore,
            @Param("batchSize")
            int batchSize
    );
}
