package com.gift.gift.domain.gift.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;

public interface GiftHistoryRepository extends JpaRepository<GiftHistory, Long> {

    Optional<GiftHistory> findBySender_IdAndIdempotencyKey(Long senderId, UUID idempotencyKey);

    Optional<GiftHistory> findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(Long id, Long recipientId, GiftStatus status);

    Optional<GiftHistory> findByOrderRecordId(Long orderRecordId);

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update GiftHistory gift
            set gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.COMPLETED,
                gift.completedAt = :completedAt,
                gift.updatedAt = :completedAt
            where gift.id = :giftId
              and gift.recipient.id = :recipientId
              and gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.WAITING_FOR_ACCEPTANCE
              and gift.deletedAt is null
            """)
    int complete(
            @Param("giftId") Long giftId,
            @Param("recipientId") Long recipientId,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            update GiftHistory gift
            set gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.CANCELED,
                gift.canceledAt = :canceledAt,
                gift.updatedAt = :canceledAt
            where gift.id = :giftId
              and gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.WAITING_FOR_ACCEPTANCE
              and gift.deletedAt is null
            """)
    int cancel(@Param("giftId") Long giftId, @Param("canceledAt") LocalDateTime canceledAt);

    @Query("""
            select gift
            from GiftHistory gift
            where gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.WAITING_FOR_ACCEPTANCE
              and gift.expiresAt <= :now
              and gift.deletedAt is null
            order by gift.expiresAt asc, gift.id asc
            """)
    List<GiftHistory> findExpired(@Param("now") LocalDateTime now, Pageable pageable);

}
