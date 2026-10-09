package com.gift.gift.domain.gift.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.gift.gift.domain.gift.support.GiftCursor;
import com.gift.gift.global.common.PaginationPolicy;

@Repository
public class GiftQueryRepository {

    private static final String SELECT_FROM = """
            SELECT new com.gift.gift.domain.gift.repository.GiftQueryRow(
                g.id,
                g.completedAt,
                counterpart.id,
                counterpart.name,
                counterpart.deletedAt,
                product.id,
                g.productNameSnapshot,
                g.productPriceSnapshot,
                g.quantity,
                CASE WHEN product.deletedAt IS NULL THEN product.brand ELSE NULL END
            )
            FROM GiftHistory g
            JOIN g.%s counterpart
            JOIN g.product product
            WHERE g.%s.id = :userId
                AND g.status = com.gift.gift.domain.gift.entity.GiftStatus.COMPLETED
                AND g.deletedAt IS NULL
            """;

    private static final String CURSOR_CONDITION = """
            AND (
                g.completedAt < :cursorCompletedAt
                OR (g.completedAt = :cursorCompletedAt AND g.id < :cursorId)
            )
            """;

    private static final String ORDER_BY = """
            ORDER BY g.completedAt DESC, g.id DESC
            """;

    private static final String COUNT_SENT_AND_RECEIVED = """
            SELECT new com.gift.gift.domain.gift.repository.GiftCountRow(
                COUNT(CASE WHEN g.sender.id = :userId THEN 1 END),
                COUNT(CASE WHEN g.recipient.id = :userId THEN 1 END)
            )
            FROM GiftHistory g
            WHERE (g.sender.id = :userId OR g.recipient.id = :userId)
                AND g.status = com.gift.gift.domain.gift.entity.GiftStatus.COMPLETED
                AND g.deletedAt IS NULL
                AND g.completedAt >= :from
                AND g.completedAt < :toExclusive
            """;

    private final EntityManager entityManager;

    public GiftQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<GiftQueryRow> findSentGifts(Long senderId, GiftCursor cursor) {
        return findGiftList(senderId, cursor, "recipient", "sender");
    }

    public List<GiftQueryRow> findReceivedGifts(Long recipientId, GiftCursor cursor) {
        return findGiftList(recipientId, cursor, "sender", "recipient");
    }

    public Optional<GiftQueryRow> findSentGiftDetail(Long giftId, Long senderId) {
        return findGiftDetail(giftId, senderId, "recipient", "sender");
    }

    public Optional<GiftQueryRow> findReceivedGiftDetail(Long giftId, Long recipientId) {
        return findGiftDetail(giftId, recipientId, "sender", "recipient");
    }

    public GiftCountRow countSentAndReceivedGifts(Long userId, LocalDateTime from, LocalDateTime toExclusive) {
        return entityManager.createQuery(COUNT_SENT_AND_RECEIVED, GiftCountRow.class)
                .setParameter("userId", userId)
                .setParameter("from", from)
                .setParameter("toExclusive", toExclusive)
                .getSingleResult();
    }

    private List<GiftQueryRow> findGiftList(
            Long userId,
            GiftCursor cursor,
            String counterpartAssociation,
            String ownerAssociation
    ) {
        String jpql = SELECT_FROM.formatted(counterpartAssociation, ownerAssociation)
                + (cursor == null ? "" : CURSOR_CONDITION)
                + ORDER_BY;
        TypedQuery<GiftQueryRow> query = entityManager.createQuery(jpql, GiftQueryRow.class)
                .setParameter("userId", userId)
                .setMaxResults(PaginationPolicy.CURSOR_FETCH_SIZE);

        if (cursor != null) {
            query.setParameter("cursorCompletedAt", cursor.lastCompletedAt());
            query.setParameter("cursorId", cursor.lastId());
        }

        return query.getResultList();
    }

    private Optional<GiftQueryRow> findGiftDetail(
            Long giftId,
            Long userId,
            String counterpartAssociation,
            String ownerAssociation
    ) {
        String jpql = SELECT_FROM.formatted(counterpartAssociation, ownerAssociation)
                + "AND g.id = :giftId";
        return entityManager.createQuery(jpql, GiftQueryRow.class)
                .setParameter("userId", userId)
                .setParameter("giftId", giftId)
                .setMaxResults(1)
                .getResultStream()
                .findFirst();
    }
}
