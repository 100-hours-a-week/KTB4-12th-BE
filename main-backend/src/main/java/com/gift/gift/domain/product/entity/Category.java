package com.gift.gift.domain.product.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public Category(String name, Category parent) {
        if (name == null || name.isBlank() || name.length() > 100) {
            throw new IllegalArgumentException(
                    "카테고리 이름은 공백이 아닌 1~100자여야 합니다."
            );
        }

        if (parent != null && (!parent.isRoot() || parent.isDeleted())) {
            throw new IllegalArgumentException(
                    "부모 카테고리는 삭제되지 않은 대분류여야 합니다."
            );
        }

        this.name = name;
        this.parent = parent;
    }

    public boolean isRoot() {
        return parent == null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
