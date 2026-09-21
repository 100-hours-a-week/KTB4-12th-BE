package com.gift.gift.domain.preference.entity;

import java.time.LocalDateTime;
import java.util.Objects;

import jakarta.persistence.*;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.product.entity.Category;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.global.common.BaseTimeEntity;

@Entity
@Table(
        name = "user_dislike_categories",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_dislike_categories_user_category",
                columnNames = {"user_id", "category_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserDislikeCategory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "user_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_user_dislike_categories_user")
    )
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "category_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(name = "fk_user_dislike_categories_category")
    )
    private Category category;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public UserDislikeCategory(User user, Category category) {
        this.user = Objects.requireNonNull(user, "user must not be null");
        this.category = Objects.requireNonNull(category, "category must not be null");

        if (!category.isRoot() || category.isDeleted()) {
            throw new IllegalArgumentException(
                    "삭제되지 않은 대분류 카테고리만 비선호로 등록할 수 있습니다."
            );
        }

    }
}
