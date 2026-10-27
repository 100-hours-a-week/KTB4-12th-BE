package com.gift.gift.domain.randombox.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.gift.gift.domain.randombox.entity.RandomBoxTicket;

public interface RandomBoxTicketRepository extends JpaRepository<RandomBoxTicket, Long> {
    Optional<RandomBoxTicket> findByIdAndAssignedUserId(Long id, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RandomBoxTicket t WHERE t.id = :id AND t.assignedUserId = :userId")
    Optional<RandomBoxTicket> findOwnedForUpdate(Long id, Long userId);

}
