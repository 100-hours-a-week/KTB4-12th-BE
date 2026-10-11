package com.gift.gift.domain.order.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.gift.gift.domain.order.entity.Order;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByOrderId(String orderId);

    Optional<Order> findByPayerIdAndIdempotencyKey(Long payerId, String idempotencyKey);
}
