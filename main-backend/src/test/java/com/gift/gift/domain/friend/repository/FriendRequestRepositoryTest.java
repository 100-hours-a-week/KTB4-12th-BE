package com.gift.gift.domain.friend.repository;

import java.time.LocalDate;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class FriendRequestRepositoryTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("Password1!");

    @Autowired private FriendRequestRepository repository;
    @Autowired private UserRepository users;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("요청자·수신자·PENDING 상태와 감사 시각을 저장한다")
    void save_persistsRequestAndAuditTimes() {
        User requester = user();
        User receiver = user();
        FriendRequest saved = repository.saveAndFlush(new FriendRequest(requester, receiver));
        entityManager.clear();

        FriendRequest found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getRequester().getId()).isEqualTo(requester.getId());
        assertThat(found.getReceiver().getId()).isEqualTo(receiver.getId());
        assertThat(found.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("동일 방향 PENDING 중복을 MySQL UNIQUE가 차단한다")
    void insert_rejectsDuplicatePending() {
        User first = user();
        User second = user();
        insert(first.getId(), second.getId(), "PENDING");
        assertThatThrownBy(() -> insert(first.getId(), second.getId(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("역방향 PENDING 중복도 MySQL UNIQUE가 차단한다")
    void insert_rejectsConversePending() {
        User first = user();
        User second = user();
        insert(first.getId(), second.getId(), "PENDING");
        assertThatThrownBy(() -> insert(second.getId(), first.getId(), "PENDING"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @EnumSource(value = FriendRequestStatus.class, names = {"ACCEPTED", "REJECTED", "CANCELED"})
    @DisplayName("종료된 요청 이력을 유지하면서 동일 쌍의 새 요청을 저장할 수 있다")
    void complete_releasesPendingPairForNewRequest(FriendRequestStatus terminal) {
        User first = user();
        User second = user();
        FriendRequest request = repository.saveAndFlush(new FriendRequest(first, second));
        request.complete(terminal);
        repository.flush();
        FriendRequest next = repository.saveAndFlush(new FriendRequest(second, first));

        assertThat(next.getId()).isNotEqualTo(request.getId());
        assertThat(next.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(repository.findPendingPair(first.getId(), second.getId()).orElseThrow().getId())
                .isEqualTo(next.getId());
        assertThat(request.getStatus()).isEqualTo(terminal);
        assertThatThrownBy(() -> request.complete(FriendRequestStatus.PENDING))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> request.complete(terminal))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("자기 요청·잘못된 상태·없는 사용자 FK를 MySQL 제약이 차단한다")
    void insert_rejectsInvalidData() {
        User first = user();
        User second = user();
        assertThatThrownBy(() -> insert(first.getId(), first.getId(), "PENDING"))
                .rootCause().isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(3819);
                    assertThat(exception.getMessage()).contains("chk_friend_requests_distinct_users");
                });
        assertThatThrownBy(() -> insert(first.getId(), second.getId(), "UNKNOWN"))
                .rootCause().isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(3819);
                    assertThat(exception.getMessage()).contains("chk_friend_requests_status");
                });
        assertThatThrownBy(() -> insert(first.getId(), Long.MAX_VALUE, "PENDING"))
                .rootCause().isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(1452);
                    assertThat(exception.getMessage()).contains("fk_friend_requests_receiver");
                });
    }

    @Test
    @DisplayName("정규화되지 않은 관계를 SQL로 직접 저장해도 CHECK가 차단한다")
    void insertFriendship_rejectsUnnormalizedPair() {
        User first = user();
        User second = user();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO friendships(user_id_1, user_id_2, created_at, updated_at)
                VALUES (?, ?, NOW(6), NOW(6))
                """, second.getId(), first.getId()))
                .rootCause().isInstanceOfSatisfying(SQLException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(3819);
                    assertThat(exception.getMessage()).contains("chk_friendships_distinct_users");
                });
    }

    private void insert(Long requester, Long receiver, String status) {
        jdbc.update("""
                INSERT INTO friend_requests(requester_id, receiver_id, status, created_at, updated_at)
                VALUES (?, ?, ?, NOW(6), NOW(6))
                """, requester, receiver, status);
    }

    private User user() {
        return users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", PASSWORD_HASH,
                "김친구", LocalDate.of(2000, 1, 1)));
    }
}
