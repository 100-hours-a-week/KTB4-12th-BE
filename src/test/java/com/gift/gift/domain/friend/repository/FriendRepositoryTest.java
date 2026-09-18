package com.gift.gift.domain.friend.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FriendRepositoryTest {

    private static String passwordHash;

    @Autowired
    private FriendRepository friendRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(12).encode("Password1!");
    }

    @Test
    @DisplayName("친구 관계를 저장하면 등록자와 친구 대상 및 감사 시각이 기록된다")
    void saveFriend_persistsUsersAndAuditTimes() {
        User user = persistedUser();
        User friendUser = persistedUser();

        Long friendId = friendRepository.saveAndFlush(new Friend(user, friendUser)).getId();
        entityManager.clear();

        Friend found = friendRepository.findById(friendId).orElseThrow();

        assertThat(found.getUser().getId()).isEqualTo(user.getId());
        assertThat(found.getFriendUser().getId()).isEqualTo(friendUser.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("삭제되지 않은 친구 관계만 단방향으로 조회한다")
    void existsActiveFriend_returnsOnlyActiveDirectionalRelation() {
        User user = persistedUser();
        User friendUser = persistedUser();
        Friend saved = friendRepository.saveAndFlush(new Friend(user, friendUser));

        assertThat(friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                user.getId(),
                friendUser.getId()
        )).isTrue();
        assertThat(friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                friendUser.getId(),
                user.getId()
        )).isFalse();

        jdbcTemplate.update(
                "UPDATE friends SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                saved.getId()
        );
        entityManager.clear();

        assertThat(friendRepository.existsByUser_IdAndFriendUser_IdAndDeletedAtIsNull(
                user.getId(),
                friendUser.getId()
        )).isFalse();
        assertThat(friendRepository.findByUser_IdAndFriendUser_Id(
                user.getId(),
                friendUser.getId()
        )).isPresent();
    }

    @Test
    @DisplayName("같은 방향의 친구 관계는 중복 저장할 수 없다")
    void saveFriend_rejectsDuplicateDirectionalRelation() {
        User user = persistedUser();
        User friendUser = persistedUser();
        friendRepository.saveAndFlush(new Friend(user, friendUser));

        assertThatThrownBy(() -> friendRepository.saveAndFlush(new Friend(user, friendUser)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("본인을 친구로 등록할 수 없다")
    void saveFriend_rejectsSelfRelation() {
        User user = persistedUser();

        assertThatThrownBy(() -> friendRepository.saveAndFlush(new Friend(user, user)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private User persistedUser() {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                passwordHash,
                "김친구",
                LocalDate.of(2000, 1, 1)
        ));
    }
}
