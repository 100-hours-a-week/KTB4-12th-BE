package com.gift.gift.domain.user.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.user.entity.LoginIpRateLimit;

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
}
