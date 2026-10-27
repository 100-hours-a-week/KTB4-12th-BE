package com.gift.gift.domain.roulette.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.roulette.entity.RouletteTicketGrant;

public interface RouletteTicketGrantRepository extends JpaRepository<RouletteTicketGrant, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO roulette_ticket_grants
                (user_id, participation_date, source_key, source_type, created_at)
            VALUES (:userId, :date, :sourceKey, :sourceType, :now)
            ON DUPLICATE KEY UPDATE source_key = source_key
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") Long userId,
            @Param("date") LocalDate date,
            @Param("sourceKey") String sourceKey,
            @Param("sourceType") String sourceType,
            @Param("now") LocalDateTime now
    );
}
