package com.gift.gift.domain.product.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(name = "product_view_stats")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductViewStats extends BaseTimeEntity {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private Integer views;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
