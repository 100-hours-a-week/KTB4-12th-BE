package com.gift.gift.domain.user.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.user.entity.UserSession;

public interface UserSessionRepository
        extends JpaRepository<UserSession, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select session
            from UserSession session
            where session.refreshTokenHash = :refreshTokenHash
            """)
    Optional<UserSession> findByRefreshTokenHashForUpdate(
            @Param("refreshTokenHash")
            String refreshTokenHash
    );
}
