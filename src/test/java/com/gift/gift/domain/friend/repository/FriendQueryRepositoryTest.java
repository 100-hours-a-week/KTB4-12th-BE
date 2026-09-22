package com.gift.gift.domain.friend.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.friend.support.FriendCursor;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.common.PaginationPolicy;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FriendQueryRepositoryTest {

    private static String passwordHash;

    @Autowired
    private FriendQueryRepository friendQueryRepository;

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
    @DisplayName("친구를 이름, 이메일 오름차순으로 조회한다")
    void findFriends_ordersByNameThenEmail() {
        User owner = persistedUser("김소유", email("owner"));
        User third = persistedUser("김다라", email("c"));
        User firstB = persistedUser("김가나", email("b"));
        User firstA = persistedUser("김가나", email("a"));
        User second = persistedUser("김나다", email("d"));
        friend(owner, third);
        friend(owner, firstB);
        friend(owner, firstA);
        friend(owner, second);

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).extracting(FriendQueryRow::userId)
                .containsExactly(firstA.getId(), firstB.getId(), second.getId(), third.getId());
    }

    @Test
    @DisplayName("조회 행에 친구 관계 ID와 친구 사용자 정보, 생일 공개 여부를 담는다")
    void findFriends_mapsFriendAndUserFields() {
        User owner = persistedUser("김소유", email("owner"));
        User friendUser = persistedUser("김민지", email("minji"), LocalDate.of(2000, 3, 14));
        Friend friend = friend(owner, friendUser);
        jdbcTemplate.update("UPDATE users SET is_birthday_public = TRUE WHERE id = ?", friendUser.getId());
        entityManager.clear();

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).containsExactly(new FriendQueryRow(
                friend.getId(),
                friendUser.getId(),
                "김민지",
                friendUser.getEmail(),
                LocalDate.of(2000, 3, 14),
                true
        ));
    }

    @Test
    @DisplayName("생일 공개를 설정하지 않은 친구는 생일 공개 여부가 false이다")
    void findFriends_returnsBirthdayNotPublic_byDefault() {
        User owner = persistedUser("김소유", email("owner"));
        friend(owner, persistedUser("김민지", email("minji")));

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).singleElement()
                .extracting(FriendQueryRow::birthdayPublic)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("다른 사용자가 등록한 친구와 반대 방향 관계는 조회하지 않는다")
    void findFriends_excludesOtherUsersAndReverseRelations() {
        User owner = persistedUser("김소유", email("owner"));
        User mine = persistedUser("김가나", email("mine"));
        User other = persistedUser("김나다", email("other"));
        User othersFriend = persistedUser("김다라", email("others-friend"));
        friend(owner, mine);
        friend(other, othersFriend);
        friend(mine, owner);

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).extracting(FriendQueryRow::userId).containsExactly(mine.getId());
    }

    @Test
    @DisplayName("삭제된 친구 관계는 조회하지 않는다")
    void findFriends_excludesDeletedRelation() {
        User owner = persistedUser("김소유", email("owner"));
        User kept = persistedUser("김가나", email("kept"));
        User removed = persistedUser("김나다", email("removed"));
        friend(owner, kept);
        Friend removedRelation = friend(owner, removed);
        jdbcTemplate.update(
                "UPDATE friends SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                removedRelation.getId()
        );
        entityManager.clear();

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).extracting(FriendQueryRow::userId).containsExactly(kept.getId());
    }

    @Test
    @DisplayName("탈퇴했거나 비활성 상태인 친구는 조회하지 않는다")
    void findFriends_excludesWithdrawnOrInactiveFriends() {
        User owner = persistedUser("김소유", email("owner"));
        User active = persistedUser("김가나", email("active"));
        User deletedStatus = persistedUser("김나다", email("deleted-status"));
        User deletedAt = persistedUser("김다라", email("deleted-at"));
        friend(owner, active);
        friend(owner, deletedStatus);
        friend(owner, deletedAt);
        jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE id = ?", deletedStatus.getId());
        jdbcTemplate.update(
                "UPDATE users SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 18, 12, 0),
                deletedAt.getId()
        );
        entityManager.clear();

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).extracting(FriendQueryRow::userId).containsExactly(active.getId());
    }

    @Test
    @DisplayName("커서 이후의 친구부터 이름 순서대로 조회한다")
    void findFriends_returnsFriendsAfterCursor() {
        User owner = persistedUser("김소유", email("owner"));
        User first = persistedUser("김가나", email("first"));
        User second = persistedUser("김나다", email("second"));
        User third = persistedUser("김다라", email("third"));
        friend(owner, first);
        Friend secondRelation = friend(owner, second);
        friend(owner, third);
        FriendCursor cursor = new FriendCursor("김나다", second.getEmail(), secondRelation.getId(), null);

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), cursor);

        assertThat(rows).extracting(FriendQueryRow::userId).containsExactly(third.getId());
    }

    @Test
    @DisplayName("이름이 같은 친구는 커서의 이메일 이후부터 조회한다")
    void findFriends_returnsSameNameFriendsAfterCursorEmail() {
        User owner = persistedUser("김소유", email("owner"));
        User firstA = persistedUser("김가나", email("a"));
        User firstB = persistedUser("김가나", email("b"));
        User firstC = persistedUser("김가나", email("c"));
        Friend firstARelation = friend(owner, firstA);
        friend(owner, firstB);
        friend(owner, firstC);
        FriendCursor cursor = new FriendCursor("김가나", firstA.getEmail(), firstARelation.getId(), null);

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), cursor);

        assertThat(rows).extracting(FriendQueryRow::userId).containsExactly(firstB.getId(), firstC.getId());
    }

    @Test
    @DisplayName("한 번에 다음 페이지 판단용 1건을 더해 최대 21건까지 조회한다")
    void findFriends_returnsAtMostFetchSize() {
        User owner = persistedUser("김소유", email("owner"));

        for (int index = 0; index < PaginationPolicy.CURSOR_FETCH_SIZE + 4; index++) {
            friend(owner, persistedUser("이" + (char) ('가' + index), email("friend")));
        }

        List<FriendQueryRow> rows = friendQueryRepository.findFriends(owner.getId(), null);

        assertThat(rows).hasSize(PaginationPolicy.CURSOR_FETCH_SIZE);
    }

    @Test
    @DisplayName("등록한 친구가 없으면 빈 목록을 반환한다")
    void findFriends_returnsEmptyList_whenNoFriends() {
        User owner = persistedUser("김소유", email("owner"));

        assertThat(friendQueryRepository.findFriends(owner.getId(), null)).isEmpty();
    }

    private Friend friend(User user, User friendUser) {
        return friendRepository.saveAndFlush(new Friend(user, friendUser));
    }

    private String email(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    private User persistedUser(String name, String email) {
        return persistedUser(name, email, LocalDate.of(2000, 1, 1));
    }

    private User persistedUser(String name, String email, LocalDate birth) {
        return userRepository.saveAndFlush(new User(email, passwordHash, name, birth));
    }
}
