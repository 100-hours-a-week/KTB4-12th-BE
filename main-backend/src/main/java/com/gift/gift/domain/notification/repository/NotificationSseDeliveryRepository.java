package com.gift.gift.domain.notification.repository;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.gift.gift.domain.notification.entity.NotificationReferenceType;
import com.gift.gift.domain.notification.entity.NotificationType;

@Repository
@RequiredArgsConstructor
public class NotificationSseDeliveryRepository {
    private final JdbcTemplate jdbc;

    public void lockRecipient(Long recipientId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id = ? FOR UPDATE", Long.class, recipientId);
    }

    public void register(String connectionId, String nodeId, Long recipientId) {
        jdbc.update("""
                INSERT INTO notification_sse_connections (id, node_id, recipient_id, expires_at)
                VALUES (?, ?, ?, TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(6)))
                """, connectionId, nodeId, recipientId);
    }

    public void deleteConnection(String connectionId) {
        jdbc.update("DELETE FROM notification_sse_connections WHERE id = ?", connectionId);
    }

    public void renew(String nodeId) {
        jdbc.update("""
                UPDATE notification_sse_connections
                SET expires_at = TIMESTAMPADD(SECOND, 120, CURRENT_TIMESTAMP(6))
                WHERE node_id = ? AND expires_at > CURRENT_TIMESTAMP(6)
                """, nodeId);
    }

    public Set<String> activeConnections(String nodeId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT id FROM notification_sse_connections
                WHERE node_id = ? AND expires_at > CURRENT_TIMESTAMP(6)
                """, String.class, nodeId));
    }

    public void deleteExpiredConnections() {
        jdbc.update("DELETE FROM notification_sse_connections WHERE expires_at <= CURRENT_TIMESTAMP(6) LIMIT 100");
    }

    public void enqueue(Long notificationId, Long recipientId) {
        jdbc.update("""
                INSERT INTO notification_sse_deliveries (notification_id, connection_id, node_id, next_attempt_at)
                SELECT ?, id, node_id, CURRENT_TIMESTAMP(6) FROM notification_sse_connections
                WHERE recipient_id = ? AND expires_at > CURRENT_TIMESTAMP(6)
                """, notificationId, recipientId);
    }

    public List<Delivery> findDue(String nodeId, int batchSize) {
        return jdbc.query("""
                SELECT d.id AS delivery_id, d.connection_id, n.*
                FROM notification_sse_deliveries d
                JOIN notification_sse_connections c ON c.id = d.connection_id
                JOIN notifications n ON n.id = d.notification_id
                WHERE d.node_id = ? AND d.completed_at IS NULL
                    AND d.next_attempt_at <= CURRENT_TIMESTAMP(6)
                    AND c.expires_at > CURRENT_TIMESTAMP(6)
                ORDER BY d.next_attempt_at, d.id LIMIT ?
                """, (rs, row) -> {
            String reference = rs.getString("reference_type");
            var readAt = rs.getTimestamp("read_at");
            return new Delivery(rs.getLong("delivery_id"), rs.getString("connection_id"), rs.getLong("recipient_id"),
                    new NotificationQueryRow(rs.getLong("id"), NotificationType.valueOf(rs.getString("type")),
                            rs.getString("title"), rs.getString("message"),
                            reference == null ? null : NotificationReferenceType.valueOf(reference),
                            rs.getObject("reference_id", Long.class), rs.getTimestamp("created_at").toLocalDateTime(),
                            readAt == null ? null : readAt.toLocalDateTime()));
        }, nodeId, batchSize);
    }

    public boolean completeBeforeSend(Long deliveryId, String nodeId) {
        return jdbc.update("""
                UPDATE notification_sse_deliveries
                SET completed_at = CURRENT_TIMESTAMP(6)
                WHERE id = ? AND node_id = ? AND completed_at IS NULL
                    AND next_attempt_at <= CURRENT_TIMESTAMP(6)
                """, deliveryId, nodeId) == 1;
    }

    public record Delivery(Long id, String connectionId, Long recipientId, NotificationQueryRow notification) {
    }
}
