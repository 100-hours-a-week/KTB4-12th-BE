package com.gift.gift.domain.friend.service;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.friend.dto.response.FriendListItem;
import com.gift.gift.domain.friend.entity.Friend;
import com.gift.gift.domain.friend.repository.FriendRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.pagination.CursorPageResponse;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@Transactional
class FriendServiceIntegrationTest {

    private static String passwordHash;

    @Autowired
    private FriendService friendService;

    @Autowired
    private FriendRepository friendRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @BeforeAll
    static void initializePasswordHash() {
        passwordHash = new BCryptPasswordEncoder(4).encode("Password1!");
    }

    @Test
    @DisplayName("친구 목록은 다음 커서로 마지막 페이지까지 중복과 누락 없이 조회한다")
    void getFriends_returnsNextAndLastPagesWithoutOverlap() {
        User owner = persistedUser("김소유");
        List<Long> friendUserIds = persistFriends(owner, 25);
        entityManager.clear();

        CursorPageResponse<FriendListItem> firstPage = friendService.getFriends(owner.getId(), null);
        CursorPageResponse<FriendListItem> lastPage = friendService.getFriends(
                owner.getId(),
                firstPage.pagination().nextCursor()
        );

        assertThat(firstPage.items()).extracting(FriendListItem::userId)
                .containsExactlyElementsOf(friendUserIds.subList(0, 20));
        assertThat(firstPage.pagination().hasNext()).isTrue();
        assertThat(firstPage.pagination().nextCursor()).isNotBlank();
        assertThat(lastPage.items()).extracting(FriendListItem::userId)
                .containsExactlyElementsOf(friendUserIds.subList(20, 25));
        assertThat(lastPage.pagination().hasNext()).isFalse();
        assertThat(lastPage.pagination().nextCursor()).isNull();
    }

    @Test
    @DisplayName("친구 목록 조회 쿼리 수는 친구 수와 무관하게 한 번이다")
    void getFriends_executesOneQueryRegardlessOfFriendCount() {
        User singleFriendOwner = persistedUser("김하나");
        persistFriends(singleFriendOwner, 1);
        User fullPageOwner = persistedUser("김스물");
        persistFriends(fullPageOwner, 21);
        entityManager.clear();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        statistics.clear();
        friendService.getFriends(singleFriendOwner.getId(), null);
        long singleFriendQueryCount = statistics.getPrepareStatementCount();

        statistics.clear();
        friendService.getFriends(fullPageOwner.getId(), null);
        long fullPageQueryCount = statistics.getPrepareStatementCount();

        assertThat(singleFriendQueryCount).isEqualTo(1);
        assertThat(fullPageQueryCount).isEqualTo(1);
    }

    // 이름은 코드 포인트 순서가 곧 정렬 순서가 되도록 연속된 한글 음절로 만든다.
    private List<Long> persistFriends(User owner, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> {
                    User friendUser = persistedUser("이" + (char) ('가' + index));
                    friendRepository.saveAndFlush(new Friend(owner, friendUser));

                    return friendUser.getId();
                })
                .toList();
    }

    private User persistedUser(String name) {
        return userRepository.saveAndFlush(new User(
                UUID.randomUUID() + "@example.com",
                passwordHash,
                name,
                LocalDate.of(2000, 1, 1)
        ));
    }
}
