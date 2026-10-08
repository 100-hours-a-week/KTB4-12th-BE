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

import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class FriendRepositoryTest {

    private static String passwordHash;

    @Autowired
    private FriendshipRepository friendRepository;

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

        Long friendId = friendRepository.saveAndFlush(new Friendship(user, friendUser)).getId();
        entityManager.clear();

        Friendship found = friendRepository.findById(friendId).orElseThrow();

        assertThat(found.getUser1().getId()).isEqualTo(user.getId());
        assertThat(found.getUser2().getId()).isEqualTo(friendUser.getId());
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(found.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("정규화된 사용자 쌍으로 삭제되지 않은 관계만 조회한다")
    void existsActiveFriend_requiresNormalizedPairAndActiveRelation() {
        User user = persistedUser();
        User friendUser = persistedUser();
        Friendship saved = friendRepository.saveAndFlush(new Friendship(user, friendUser));

        assertThat(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
                user.getId(),
                friendUser.getId()
        )).isTrue();
        assertThat(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
                friendUser.getId(),
                user.getId()
        )).isFalse();

        jdbcTemplate.update(
                "UPDATE friendships SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                saved.getId()
        );
        entityManager.clear();

        assertThat(friendRepository.existsByUser1_IdAndUser2_IdAndDeletedAtIsNull(
                user.getId(),
                friendUser.getId()
        )).isFalse();
        assertThat(friendRepository.findByUser1_IdAndUser2_Id(
                user.getId(),
                friendUser.getId()
        )).isPresent();
    }

    @Test
    @DisplayName("같은 방향의 친구 관계는 중복 저장할 수 없다")
    void saveFriend_rejectsDuplicateDirectionalRelation() {
        User user = persistedUser();
        User friendUser = persistedUser();
        friendRepository.saveAndFlush(new Friendship(user, friendUser));

        assertThatThrownBy(() -> friendRepository.saveAndFlush(new Friendship(user, friendUser)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("역방향으로 생성해도 사용자 ID가 정규화되고 같은 쌍은 중복 저장할 수 없다")
    void saveFriend_normalizesAndRejectsReverseDuplicate() {
        User first = persistedUser();
        User second = persistedUser();
        Friendship saved = friendRepository.saveAndFlush(new Friendship(second, first));

        assertThat(saved.getUser1().getId()).isEqualTo(first.getId());
        assertThat(saved.getUser2().getId()).isEqualTo(second.getId());
        assertThatThrownBy(() -> friendRepository.saveAndFlush(new Friendship(first, second)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("삭제 후 복구해도 관계 ID와 최초 생성 시각을 유지한다")
    void restore_keepsIdentityAndCreationTime() {
        User first = persistedUser();
        User second = persistedUser();
        Friendship saved = friendRepository.saveAndFlush(new Friendship(first, second));
        Long id = saved.getId();
        LocalDateTime createdAt = saved.getCreatedAt();
        saved.remove(LocalDateTime.now());
        friendRepository.flush();
        assertThat(saved.getDeletedAt()).isNotNull();

        saved.restore();
        friendRepository.flush();
        entityManager.clear();
        Friendship restored = friendRepository.findById(id).orElseThrow();
        assertThat(restored.getCreatedAt()).isEqualTo(createdAt);
        assertThat(restored.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("본인을 친구로 등록할 수 없다")
    void saveFriend_rejectsSelfRelation() {
        User user = persistedUser();

        assertThatThrownBy(() -> friendRepository.saveAndFlush(new Friendship(user, user)))
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
