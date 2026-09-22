package com.gift.gift.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.user.entity.TermConsent;

public interface TermConsentRepository extends JpaRepository<TermConsent, Long> {
}
