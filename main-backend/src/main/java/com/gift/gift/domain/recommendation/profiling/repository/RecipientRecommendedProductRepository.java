package com.gift.gift.domain.recommendation.profiling.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.recommendation.profiling.entity.RecipientRecommendedProduct;

public interface RecipientRecommendedProductRepository
        extends JpaRepository<RecipientRecommendedProduct, Long> {

    List<RecipientRecommendedProduct>
    findAllByRecipientProfile_Recipient_IdAndSourceVersionOrderByRankOrderAsc(
            Long recipientId,
            long sourceVersion
    );

    @Query("""
        select recommendation.product.id
        from RecipientRecommendedProduct recommendation
        where recommendation.recipientProfile.recipient.id = :recipientId
          and recommendation.sourceVersion = :sourceVersion
          and recommendation.product.deletedAt is null
          and recommendation.product.quantity > 0
        order by recommendation.rankOrder asc
        """)
    List<Long> findAvailableRecommendedProductIds(
            @Param("recipientId") Long recipientId,
            @Param("sourceVersion") long sourceVersion
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from RecipientRecommendedProduct recommendation
            where recommendation.recipientProfile.recipient.id =
                  :recipientId
            """)
    int deleteAllByRecipientId(
            @Param("recipientId") Long recipientId
    );
}
