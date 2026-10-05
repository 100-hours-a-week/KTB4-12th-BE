package com.gift.gift.domain.notification.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.notification.entity.Notification;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    boolean existsByDeduplicationKey(String deduplicationKey);

    Optional<Notification> findByIdAndRecipientIdAndDeletedAtIsNull(Long id, Long recipientId);
}
