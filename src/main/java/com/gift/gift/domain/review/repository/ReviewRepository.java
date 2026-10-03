package com.gift.gift.domain.review.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.review.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Optional<Review> findByGiftHistory_IdAndUser_IdAndDeletedAtIsNull(Long giftId, Long userId);

}
