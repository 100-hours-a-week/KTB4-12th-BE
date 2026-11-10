package com.gift.gift.domain.roulette.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.roulette.entity.RouletteAccount;

public interface RouletteAccountRepository extends JpaRepository<RouletteAccount, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO roulette_accounts (user_id) VALUES (:userId)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from RouletteAccount account where account.userId = :userId")
    Optional<RouletteAccount> findByUserIdForUpdate(@Param("userId") Long userId);
}
