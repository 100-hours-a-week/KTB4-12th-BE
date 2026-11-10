package com.gift.gift.domain.randombox.entity;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "random_box_prizes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RandomBoxPrize {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 200)
    private String name;

    @Column(name = "voucher_code", nullable = false, updatable = false, length = 1000)
    private String voucherCode;

    @Column(name = "voucher_hash", nullable = false, updatable = false, length = 64)
    private String voucherHash;
}
