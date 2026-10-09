package com.gift.gift.domain.recommendation.entity;

import java.util.Objects;

import jakarta.persistence.CheckConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.gift.gift.domain.product.entity.Product;
import com.gift.gift.global.common.BaseTimeEntity;

@Getter
@Entity
@Table(
        name = "recipient_recommended_products",
        uniqueConstraints = {
                @UniqueConstraint(
                        name =
                                "uk_rec_products_recipient_version_product",
                        columnNames = {
                                "recipient_id",
                                "source_version",
                                "product_id"
                        }
                ),
                @UniqueConstraint(
                        name =
                                "uk_rec_products_recipient_version_rank",
                        columnNames = {
                                "recipient_id",
                                "source_version",
                                "rank_order"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_rec_products_product",
                        columnList = "product_id"
                )
        },
        check = {
                @CheckConstraint(
                        name = "chk_rec_products_rank_order",
                        constraint = "rank_order BETWEEN 1 AND 30"
                ),
                @CheckConstraint(
                        name = "chk_rec_products_source_version",
                        constraint = "source_version >= 0"
                )
        }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RecipientRecommendedProduct
        extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "recipient_id",
            referencedColumnName = "recipient_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rec_products_recipient"
            )
    )
    private RecipientProfile recipientProfile;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "product_id",
            nullable = false,
            updatable = false,
            foreignKey = @ForeignKey(
                    name = "fk_rec_products_product"
            )
    )
    private Product product;

    @Min(1)
    @Max(30)
    @Column(
            name = "rank_order",
            nullable = false,
            columnDefinition = "TINYINT UNSIGNED"
    )
    private int rankOrder;

    @Min(0)
    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    public RecipientRecommendedProduct(
            RecipientProfile recipientProfile,
            Product product,
            int rankOrder,
            long sourceVersion
    ) {
        this.recipientProfile = Objects.requireNonNull(
                recipientProfile,
                "수신자 프로파일은 null일 수 없습니다."
        );

        this.product = Objects.requireNonNull(
                product,
                "추천 상품은 null일 수 없습니다."
        );

        validateRankOrder(rankOrder);
        validateSourceVersion(sourceVersion);

        this.rankOrder = rankOrder;
        this.sourceVersion = sourceVersion;
    }

    private static void validateRankOrder(int rankOrder) {
        if (rankOrder < 1 || rankOrder > 30) {
            throw new IllegalArgumentException(
                    "추천 순위는 1부터 30까지여야 합니다."
            );
        }
    }

    private static void validateSourceVersion(
            long sourceVersion
    ) {
        if (sourceVersion < 0) {
            throw new IllegalArgumentException(
                    "추천 결과 버전은 0 이상이어야 합니다."
            );
        }
    }
}
