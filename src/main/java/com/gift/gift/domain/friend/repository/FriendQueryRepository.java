package com.gift.gift.domain.friend.repository;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Repository;

import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.global.common.PaginationPolicy;

@Repository
public class FriendQueryRepository {

    private static final String SELECT_FRIENDS = """
            SELECT new com.gift.gift.domain.friend.repository.FriendQueryRow(
                f.id,
                friendUser.id,
                friendUser.name,
                friendUser.email,
                friendUser.birth,
                friendUser.isBirthdayPublic
            )
            FROM Friend f
            JOIN f.friendUser friendUser
            WHERE f.user.id = :userId
                AND f.deletedAt IS NULL
                AND friendUser.status = com.gift.gift.domain.user.entity.UserStatus.ACTIVE
                AND friendUser.deletedAt IS NULL
            """;

    // 정렬 키(이름 → 이메일 → 친구 관계 ID)와 같은 순서여야 페이지 경계에서 항목이 중복되거나 누락되지 않는다.
    private static final String CURSOR_CONDITION = """
            AND (
                friendUser.name > :cursorName
                OR (friendUser.name = :cursorName AND friendUser.email > :cursorEmail)
                OR (
                    friendUser.name = :cursorName
                    AND friendUser.email = :cursorEmail
                    AND f.id > :cursorFriendId
                )
            )
            """;

    private static final String ORDER_BY = """
            ORDER BY friendUser.name ASC, friendUser.email ASC, f.id ASC
            """;

    private final EntityManager entityManager;

    public FriendQueryRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public List<FriendQueryRow> findFriends(Long userId, FriendCursor cursor) {
        String jpql = SELECT_FRIENDS
                + (cursor == null ? "" : CURSOR_CONDITION)
                + ORDER_BY;
        TypedQuery<FriendQueryRow> query = entityManager.createQuery(jpql, FriendQueryRow.class)
                .setParameter("userId", userId)
                .setMaxResults(PaginationPolicy.CURSOR_FETCH_SIZE);

        if (cursor != null) {
            query.setParameter("cursorName", cursor.name());
            query.setParameter("cursorEmail", cursor.email());
            query.setParameter("cursorFriendId", cursor.friendId());
        }

        return query.getResultList();
    }
}
