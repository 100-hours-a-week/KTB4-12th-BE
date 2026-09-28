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
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class FriendSearchQueryRepositoryTest {

    private static String passwordHash;

    @Autowired
    private FriendQueryRepository friendQueryRepository;

    @Autowired
    private FriendRepository friendRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash =
                new BCryptPasswordEncoder(4)
                        .encode("Password1!");
    }

    @Test
    @DisplayName(
            "이름을 부분 일치하고 영문 대소문자를 구분하지 않고 검색한다"
    )
    void findFriendsByName_matchesPartialNameIgnoringCase() {
        User owner = persistedUser("김소유");
        User matchedEnglish = persistedUser("MinSeo");
        User matchedKorean = persistedUser("김민지");
        User unmatched = persistedUser("박민수");

        friend(owner, matchedEnglish);
        friend(owner, matchedKorean);
        friend(owner, unmatched);

        List<FriendQueryRow> englishResult =
                friendQueryRepository.findFriendsByName(
                        owner.getId(),
                        "min",
                        null
                );

        List<FriendQueryRow> koreanResult =
                friendQueryRepository.findFriendsByName(
                        owner.getId(),
                        "민지",
                        null
                );

        assertThat(englishResult)
                .extracting(FriendQueryRow::userId)
                .containsExactly(matchedEnglish.getId());

        assertThat(koreanResult)
                .extracting(FriendQueryRow::userId)
                .containsExactly(matchedKorean.getId());
    }

    @Test
    @DisplayName(
            "퍼센트와 언더스코어는 와일드카드가 아닌 일반 문자로 검색한다"
    )
    void findFriendsByName_treatsSqlWildcardsAsLiteralCharacters() {
        User owner = persistedUser("김소유");
        User percentName = persistedUser("김가나");
        User underscoreName = persistedUser("김나다");
        User ordinaryName = persistedUser("김다라");

        friend(owner, percentName);
        friend(owner, underscoreName);
        friend(owner, ordinaryName);

        jdbcTemplate.update(
                "UPDATE users SET name = ? WHERE id = ?",
                "김%민",
                percentName.getId()
        );
        jdbcTemplate.update(
                "UPDATE users SET name = ? WHERE id = ?",
                "김_민",
                underscoreName.getId()
        );
        jdbcTemplate.update(
                "UPDATE users SET name = ? WHERE id = ?",
                "김가민",
                ordinaryName.getId()
        );

        entityManager.clear();

        List<FriendQueryRow> percentResult =
                friendQueryRepository.findFriendsByName(
                        owner.getId(),
                        "%",
                        null
                );

        List<FriendQueryRow> underscoreResult =
                friendQueryRepository.findFriendsByName(
                        owner.getId(),
                        "_",
                        null
                );

        assertThat(percentResult)
                .extracting(FriendQueryRow::userId)
                .containsExactly(percentName.getId());

        assertThat(underscoreResult)
                .extracting(FriendQueryRow::userId)
                .containsExactly(underscoreName.getId());
    }

    @Test
    @DisplayName(
            "삭제된 친구 관계와 비활성 또는 탈퇴한 회원은 검색하지 않는다"
    )
    void findFriendsByName_excludesDeletedRelationsAndInactiveUsers() {
        User owner = persistedUser("김소유");
        User active = persistedUser("검색활성");
        User deletedRelationUser = persistedUser("검색삭제");
        User inactive = persistedUser("검색비활성");
        User withdrawn = persistedUser("검색탈퇴");

        friend(owner, active);

        Friend deletedRelation =
                friend(owner, deletedRelationUser);

        friend(owner, inactive);
        friend(owner, withdrawn);

        jdbcTemplate.update(
                "UPDATE friends SET deleted_at = ? WHERE id = ?",
                LocalDateTime.of(2026, 9, 23, 12, 0),
                deletedRelation.getId()
        );
        jdbcTemplate.update(
                "UPDATE users SET status = 'INACTIVE' WHERE id = ?",
                inactive.getId()
        );
        jdbcTemplate.update(
                """
                UPDATE users
                SET status = 'DELETED', deleted_at = ?
                WHERE id = ?
                """,
                LocalDateTime.of(2026, 9, 23, 12, 0),
                withdrawn.getId()
        );

        entityManager.clear();

        List<FriendQueryRow> result =
                friendQueryRepository.findFriendsByName(
                        owner.getId(),
                        "검색",
                        null
                );

        assertThat(result)
                .extracting(FriendQueryRow::userId)
                .containsExactly(active.getId());
    }

    private Friend friend(
            User owner,
            User friendUser
    ) {
        return friendRepository.saveAndFlush(
                new Friend(owner, friendUser)
        );
    }

    private User persistedUser(String name) {
        return userRepository.saveAndFlush(
                new User(
                        UUID.randomUUID() + "@example.com",
                        passwordHash,
                        name,
                        LocalDate.of(2000, 1, 1)
                )
        );
    }
}
