package com.gift.gift.domain.preference.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.preference.entity.UserGiftPreference;

public interface UserGiftPreferenceRepository extends JpaRepository<UserGiftPreference, Long> {

    Optional<UserGiftPreference> findByUser_IdAndDeletedAtIsNull(Long userId);
}
