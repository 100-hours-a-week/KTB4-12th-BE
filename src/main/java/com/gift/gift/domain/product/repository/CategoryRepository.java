package com.gift.gift.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.product.entity.Category;

public interface CategoryRepository extends JpaRepository<Category, Long> {
}
