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

import com.gift.gift.domain.auth.entity.LoginIpRateLimit;

public interface LoginIpRateLimitRepository
        extends JpaRepository<LoginIpRateLimit, String> {

    @Modifying(flushAutomatically = true)
    @Query(
            value = """
                    insert into login_ip_rate_limits (
                        identifier_hash,
                        available_tokens,
                        last_refilled_at,
                        created_at,
                        updated_at
                    ) values (
                        :identifierHash,
                        10.000,
                        :now,
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
            select rateLimit
            from LoginIpRateLimit rateLimit
            where rateLimit.identifierHash = :identifierHash
            """)
    Optional<LoginIpRateLimit> findByIdentifierHashForUpdate(
            @Param("identifierHash") String identifierHash
    );

    @Query(
            value = """
                select identifier_hash
                from login_ip_rate_limits
                where updated_at <= :inactiveBefore
                order by updated_at, identifier_hash
                limit :batchSize
                """,
            nativeQuery = true
    )
    List<String> findInactiveCandidates(
            @Param("inactiveBefore") LocalDateTime inactiveBefore,
            @Param("batchSize") int batchSize
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query(
            value = """
                delete rate_limit
                from login_ip_rate_limits as rate_limit
                    force index (PRIMARY)
                where rate_limit.identifier_hash = :identifierHash
                    and rate_limit.updated_at <= :inactiveBefore
                """,
            nativeQuery = true
    )
    int deleteInactiveByIdentifierHash(
            @Param("identifierHash") String identifierHash,
            @Param("inactiveBefore") LocalDateTime inactiveBefore
    );
}
