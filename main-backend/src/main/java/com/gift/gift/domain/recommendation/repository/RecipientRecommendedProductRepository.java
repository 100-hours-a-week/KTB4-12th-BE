package com.gift.gift.domain.recommendation.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.recommendation.entity.RecipientRecommendedProduct;

public interface RecipientRecommendedProductRepository
        extends JpaRepository<RecipientRecommendedProduct, Long> {

    List<RecipientRecommendedProduct>
    findAllByRecipientProfile_Recipient_IdAndSourceVersionOrderByRankOrderAsc(
            Long recipientId,
            long sourceVersion
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
