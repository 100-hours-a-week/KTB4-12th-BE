package com.gift.gift.domain.gift.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.gift.gift.domain.gift.entity.GiftHistory;
import com.gift.gift.domain.gift.entity.GiftStatus;

public interface GiftHistoryRepository extends JpaRepository<GiftHistory, Long> {

    Optional<GiftHistory> findByIdAndRecipient_IdAndStatusAndDeletedAtIsNull(Long id, Long recipientId, GiftStatus status);

    Optional<GiftHistory> findByOrderRecordId(Long orderRecordId);

    Optional<GiftHistory> findByIdAndRecipient_IdAndDeletedAtIsNull(Long giftId, Long recipientId);

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
              and gift.expiresAt > :completedAt
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
    int cancel(
            @Param("giftId") Long giftId,
            @Param("canceledAt") LocalDateTime canceledAt
    );

    @Query("""
            select new com.gift.gift.domain.gift.repository.GiftExpirationCandidate(
                gift.id,
                gift.sender.id,
                gift.orderRecordId,
                gift.expiresAt
            )
            from GiftHistory gift
            where gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.WAITING_FOR_ACCEPTANCE
              and gift.expiresAt <= :cutoff
              and gift.deletedAt is null
            order by gift.expiresAt asc, gift.id asc
            """)
    List<GiftExpirationCandidate> findExpired(
            @Param("cutoff") LocalDateTime cutoff,
            Pageable pageable
    );

    @Query("""
            select new com.gift.gift.domain.gift.repository.GiftExpirationCandidate(
                gift.id,
                gift.sender.id,
                gift.orderRecordId,
                gift.expiresAt
            )
            from GiftHistory gift
            where gift.status =
                    com.gift.gift.domain.gift.entity.GiftStatus.WAITING_FOR_ACCEPTANCE
              and gift.expiresAt <= :cutoff
              and gift.deletedAt is null
              and (
                    gift.expiresAt > :cursorExpiresAt
                    or (
                        gift.expiresAt = :cursorExpiresAt
                        and gift.id > :cursorId
                    )
              )
            order by gift.expiresAt asc, gift.id asc
            """)
    List<GiftExpirationCandidate> findExpiredAfter(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("cursorExpiresAt") LocalDateTime cursorExpiresAt,
            @Param("cursorId") Long cursorId,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select gift from GiftHistory gift
            where gift.id = :giftId and gift.recipient.id = :recipientId
              and gift.deletedAt is null
            """)
    Optional<GiftHistory> findForAcceptance(
            @Param("giftId") Long giftId, @Param("recipientId") Long recipientId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select gift from GiftHistory gift
            where gift.orderRecordId = :orderRecordId
            """)
    Optional<GiftHistory> findForCancellation(@Param("orderRecordId") Long orderRecordId);
}
