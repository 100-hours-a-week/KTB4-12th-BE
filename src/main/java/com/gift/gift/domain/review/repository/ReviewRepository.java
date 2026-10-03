package com.gift.gift.domain.review.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.review.entity.Review;

public interface ReviewRepository extends JpaRepository<Review, Long> {
}
