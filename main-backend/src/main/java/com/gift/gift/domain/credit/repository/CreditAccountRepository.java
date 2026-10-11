package com.gift.gift.domain.credit.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.credit.entity.CreditAccount;

public interface CreditAccountRepository extends JpaRepository<CreditAccount, Long> {

    Optional<CreditAccount> findByUserId(Long userId);

    boolean existsByUserId(Long userId);

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update CreditAccount account
            set account.balance = account.balance + :amount
            where account.userId = :userId
              and :amount > 0
            """)
    int increaseBalance(
            @Param("userId") Long userId,
            @Param("amount") long amount
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update CreditAccount account
            set account.balance = account.balance - :amount
            where account.userId = :userId
              and :amount > 0
              and account.balance >= :amount
            """)
    int decreaseBalance(
            @Param("userId") Long userId,
            @Param("amount") long amount
    );
}
