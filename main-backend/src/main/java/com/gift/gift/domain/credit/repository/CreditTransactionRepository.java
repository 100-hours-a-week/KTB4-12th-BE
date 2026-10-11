package com.gift.gift.domain.credit.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.credit.entity.CreditTransaction;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, Long> {

    boolean existsByDeduplicationKey(String deduplicationKey);

    Optional<CreditTransaction> findByDeduplicationKey(String deduplicationKey);
}
