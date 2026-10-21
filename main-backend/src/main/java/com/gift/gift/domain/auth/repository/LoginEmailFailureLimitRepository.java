package com.gift.gift.domain.auth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.auth.entity.LoginEmailFailureLimit;

public interface LoginEmailFailureLimitRepository
        extends JpaRepository<LoginEmailFailureLimit, String> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    insert into login_email_failure_limits (
                        identifier_hash,
                        failure_count,
                        window_started_at,
                        backoff_level,
                        blocked_until,
                        created_at,
                        updated_at
                    ) values (
                        :identifierHash,
                        0,
                        :now,
                        0,
                        null,
                        :now,
                        :now
                    )
                    on duplicate key update
                        identifier_hash = identifier_hash
                    """,
            nativeQuery = true
    )
    int insertIfAbsent(
            @Param("identifierHash") String identifierHash,
            @Param("now") LocalDateTime now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select failureLimit
            from LoginEmailFailureLimit failureLimit
            where failureLimit.identifierHash = :identifierHash
            """)
    Optional<LoginEmailFailureLimit>
    findByIdentifierHashForUpdate(
            @Param("identifierHash")
            String identifierHash
    );

    @Query(
            value = """
                select identifier_hash
                from login_email_failure_limits
                where updated_at <= :inactiveBefore
                    and (
                        blocked_until is null
                        or blocked_until <= :now
                    )
                order by updated_at, identifier_hash
                limit :batchSize
                """,
            nativeQuery = true
    )

    List<String> findInactiveCandidates(
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("now") LocalDateTime now,
            @Param("batchSize") int batchSize
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                delete failure_limit
                from login_email_failure_limits as failure_limit
                    force index (PRIMARY)
                where failure_limit.identifier_hash = :identifierHash
                    and failure_limit.updated_at <= :inactiveBefore
                    and (
                        failure_limit.blocked_until is null
                        or failure_limit.blocked_until <= :now
                    )
                """,
            nativeQuery = true
    )
    int deleteInactiveByIdentifierHash(
            @Param("identifierHash") String identifierHash,
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("now") LocalDateTime now
    );
}
