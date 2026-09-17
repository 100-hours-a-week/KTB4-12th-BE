package com.gift.gift.domain.product.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.product.entity.Product;

public interface ProductRepository extends JpaRepository<Product, Long> {
}
