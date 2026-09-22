package com.gift.gift.domain.user.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.gift.gift.domain.user.entity.Term;

public interface TermRepository extends JpaRepository<Term, Long> {

    @Query("""
            SELECT term
            FROM Term term
            WHERE term.deletedAt IS NULL
              AND term.isRequired = true
              AND NOT EXISTS (
                  SELECT newer.id
                  FROM Term newer
                  WHERE newer.termCode = term.termCode
                    AND newer.deletedAt IS NULL
                    AND newer.version > term.version
              )
            ORDER BY term.termCode ASC, term.id ASC
            """)
    List<Term> findCurrentRequiredTerms();
}
