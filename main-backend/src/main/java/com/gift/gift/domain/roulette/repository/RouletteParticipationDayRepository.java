package com.gift.gift.domain.roulette.repository;

import java.time.LocalDate;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.roulette.entity.RouletteParticipationDay;

public interface RouletteParticipationDayRepository extends JpaRepository<RouletteParticipationDay, Long> {

    Optional<RouletteParticipationDay> findByUserIdAndParticipationDate(Long userId, LocalDate date);

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO roulette_days (user_id, participation_date) VALUES (:userId, :date)
            ON DUPLICATE KEY UPDATE user_id = user_id
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId, @Param("date") LocalDate date);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select day from RouletteParticipationDay day
            where day.userId = :userId and day.participationDate = :date
            """)
    Optional<RouletteParticipationDay> findByUserIdAndDateForUpdate(
            @Param("userId") Long userId, @Param("date") LocalDate date
    );
}
