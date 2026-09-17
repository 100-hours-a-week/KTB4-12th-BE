package com.gift.gift.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.user.entity.Term;

public interface TermRepository extends JpaRepository<Term, Long> {
}
