package com.gift.gift.domain.review.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.review.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByGiftHistory_IdAndUser_IdAndDeletedAtIsNull(Long giftId, Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT review
            FROM Review review
            WHERE review.giftHistory.id = :giftId
              AND review.user.id = :userId
              AND review.deletedAt IS NULL
            """)
    Optional<Review> findActiveReviewForUpdate(
            @Param("giftId") Long giftId,
            @Param("userId") Long userId
    );
}
