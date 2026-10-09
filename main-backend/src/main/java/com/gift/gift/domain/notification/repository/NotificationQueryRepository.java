package com.gift.gift.domain.notification.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.gift.gift.domain.notification.support.NotificationCursor;
import com.gift.gift.global.common.PaginationPolicy;

@Repository
public class NotificationQueryRepository {

    private static final String SELECT_FROM = """
            SELECT new com.gift.gift.domain.notification.repository.NotificationQueryRow(
                n.id,
                n.type,
                n.title,
                n.message,
                n.referenceType,
                n.referenceId,
                n.createdAt,
                n.readAt
            )
            FROM Notification n
            WHERE n.recipientId = :recipientId
                AND n.deletedAt IS NULL
            """;

    private static final String CURSOR_CONDITION = """
            AND (
                n.createdAt < :cursorCreatedAt
                OR (n.createdAt = :cursorCreatedAt AND n.id < :cursorId)
            )
            """;

    private static final String ORDER_BY = """
            ORDER BY n.createdAt DESC, n.id DESC
            """;

    private final EntityManager entityManager;

    public NotificationQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<NotificationQueryRow> findNotifications(Long recipientId, NotificationCursor cursor) {
        String jpql = SELECT_FROM + (cursor == null ? "" : CURSOR_CONDITION) + ORDER_BY;
        TypedQuery<NotificationQueryRow> query = entityManager
                .createQuery(jpql, NotificationQueryRow.class)
                .setParameter("recipientId", recipientId)
                .setMaxResults(PaginationPolicy.CURSOR_FETCH_SIZE);

        if (cursor != null) {
            query.setParameter("cursorCreatedAt", cursor.lastCreatedAt());
            query.setParameter("cursorId", cursor.lastId());
        }

        return query.getResultList();
    }

    public long countUnreadNotifications(Long recipientId) {
        return entityManager.createQuery("""
                SELECT COUNT(n)
                FROM Notification n
                WHERE n.recipientId = :recipientId
                    AND n.readAt IS NULL
                    AND n.deletedAt IS NULL
                """, Long.class)
                .setParameter("recipientId", recipientId)
                .getSingleResult();
    }
}
