package com.gift.gift.domain.randombox.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "random_box_campaigns")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RandomBoxCampaign {
    @Id
    private Integer id;

    @Column(nullable = false)
    private boolean ready;
}
