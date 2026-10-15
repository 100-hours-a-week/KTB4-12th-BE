package com.gift.gift.domain.friend.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.gift.gift.domain.credit.service.FriendshipCreditService;
import com.gift.gift.domain.credit.service.SignupCreditService;
import com.gift.gift.domain.friend.dto.request.FriendRequestCreateRequest;
import com.gift.gift.domain.friend.dto.response.FriendRequestResponse;
import com.gift.gift.domain.friend.entity.FriendRequest;
import com.gift.gift.domain.friend.entity.FriendRequestStatus;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.friend.repository.FriendRequestRepository;
import com.gift.gift.domain.friend.repository.FriendshipRepository;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;
import com.gift.gift.global.exception.BusinessException;
import com.gift.gift.global.exception.ErrorCode;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FriendRequestConcurrencyIntegrationTest {
    @Autowired private FriendRequestService service;
    @Autowired private FriendService friendService;
    @Autowired private FriendRequestRepository requests;
    @Autowired private FriendshipRepository friendships;
    @Autowired private UserRepository users;
    @Autowired private SignupCreditService signupCreditService;
    @Autowired private FriendshipCreditService friendshipCreditService;
    @Autowired private JdbcTemplate jdbc;

    private final List<Long> userIds = new ArrayList<>();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("중복·역방향 동시 요청은 PENDING과 알림을 하나만 생성하고 충돌을 구분한다")
    void create_concurrentlyKeepsOnePendingRequest(boolean reverse) throws Exception {
        User first = user();
        User second = user();
        List<Object> results = race(
                () -> create(first, second),
                () -> reverse ? create(second, first) : create(first, second));

        assertThat(results.stream().filter(FriendRequestResponse.class::isInstance).count()).isEqualTo(1);
        assertThat(results.stream().filter(ErrorCode.class::isInstance).toList())
                .containsExactly(reverse ? ErrorCode.CONVERSE_REQUEST_EXISTS : ErrorCode.FRIEND_ALREADY_REQUESTED);
        FriendRequestResponse winner = results.stream().filter(FriendRequestResponse.class::isInstance)
                .map(FriendRequestResponse.class::cast).findFirst().orElseThrow();
        FriendRequest saved = requests.findById(winner.requestId()).orElseThrow();
        assertThat(saved.getRequester().getId()).isEqualTo(winner.requesterId());
        assertThat(saved.getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        assertThat(requestCount(first, second)).isEqualTo(1);
        assertThat(relationCount(first, second)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE recipient_id IN (?, ?)",
                Long.class, first.getId(), second.getId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("동시 수락은 모두 성공하고 신규·삭제된 관계 모두 한 행만 활성화한다")
    void accept_concurrentlyCreatesOrRestoresOneRelation(boolean restore) throws Exception {
        User first = user();
        User second = user();
        Friendship old = null;
        if (restore) {
            old = friendships.saveAndFlush(new Friendship(first, second));
            old = friendships.findById(old.getId()).orElseThrow();
            friendshipCreditService.grant(old);
            friendService.remove(first.getId(), old.getId());
        }
        Long id = requests.saveAndFlush(new FriendRequest(first, second)).getId();
        List<Object> results = race(() -> service.accept(second.getId(), id),
                () -> service.accept(second.getId(), id));

        assertThat(results).allSatisfy(result -> {
            assertThat(result).isInstanceOf(FriendRequestResponse.class);
            assertThat(((FriendRequestResponse) result).status()).isEqualTo(FriendRequestStatus.ACCEPTED);
            assertThat(((FriendRequestResponse) result).requestId()).isEqualTo(id);
        });
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
        assertThat(relationCount(first, second)).isEqualTo(1);
        Friendship relation = friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId()).orElseThrow();
        assertThat(relation.getDeletedAt()).isNull();
        if (restore) {
            assertThat(relation.getId()).isEqualTo(old.getId());
            assertThat(relation.getCreatedAt()).isEqualTo(old.getCreatedAt());
        }
        assertRewardedOnce(first);
        assertRewardedOnce(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reject", "cancel"})
    @DisplayName("수락과 거절·취소 경쟁은 하나만 성공하고 관계 상태는 승자의 요청 상태와 일치한다")
    void complete_concurrentDifferentActionsHaveOneWinner(String other) throws Exception {
        User first = user();
        User second = user();
        Long id = requests.saveAndFlush(new FriendRequest(first, second)).getId();
        List<Object> results = race(() -> service.accept(second.getId(), id),
                () -> other.equals("reject") ? service.reject(second.getId(), id) : service.cancel(first.getId(), id));

        assertThat(results.stream().filter(ErrorCode.class::isInstance).toList())
                .containsExactly(ErrorCode.FRIEND_REQUEST_ALREADY_PROCESSED);
        List<FriendRequestResponse> winners = results.stream().filter(FriendRequestResponse.class::isInstance)
                .map(FriendRequestResponse.class::cast).toList();
        assertThat(winners).hasSize(1);
        FriendRequestStatus status = winners.getFirst().status();
        assertThat(status).isIn(FriendRequestStatus.ACCEPTED,
                other.equals("reject") ? FriendRequestStatus.REJECTED : FriendRequestStatus.CANCELED);
        assertThat(requests.findById(id).orElseThrow().getStatus()).isEqualTo(status);
        assertThat(requestCount(first, second)).isEqualTo(1);
        assertThat(relationCount(first, second)).isEqualTo(status == FriendRequestStatus.ACCEPTED ? 1 : 0);
        assertThat(requests.findPendingPair(first.getId(), second.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("관계 삭제와 양방향 재요청 경쟁 후 새 수락은 기존 관계를 복구하고 종료 이력을 보존한다")
    void remove_concurrentRequestCanBeRetriedAndRestoresRelation(boolean reverse) throws Exception {
        User first = user();
        User second = user();
        Long previous = create(first, second).requestId();
        service.accept(second.getId(), previous);
        Friendship old = friendships.findByUser1_IdAndUser2_Id(first.getId(), second.getId()).orElseThrow();
        User requester = reverse ? second : first;
        User receiver = reverse ? first : second;
        List<Object> results = race(() -> {
            friendService.remove(first.getId(), old.getId());
            return null;
        }, () -> create(requester, receiver));

        assertThat(results.getFirst()).isNull();
        Object result = results.getLast();
        if (result instanceof ErrorCode) {
            assertThat(result).isEqualTo(ErrorCode.FRIEND_ALREADY_EXISTS);
            assertThat(requestCount(first, second)).isEqualTo(1);
            result = create(requester, receiver);
        }
        assertThat(result).isInstanceOf(FriendRequestResponse.class);
        Long next = ((FriendRequestResponse) result).requestId();
        assertThat(next).isNotEqualTo(previous);
        assertThat(friendships.findById(old.getId()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(requests.findById(next).orElseThrow().getStatus()).isEqualTo(FriendRequestStatus.PENDING);
        service.accept(receiver.getId(), next);
        Friendship restored = friendships.findById(old.getId()).orElseThrow();
        assertThat(restored.getDeletedAt()).isNull();
        assertThat(restored.getCreatedAt()).isEqualTo(old.getCreatedAt());
        assertThat(relationCount(first, second)).isEqualTo(1);
        assertThat(requestCount(first, second)).isEqualTo(2);
        assertThat(requests.findById(previous).orElseThrow().getStatus()).isEqualTo(FriendRequestStatus.ACCEPTED);
    }

    private List<Object> race(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = List.of(first, second).stream().map(action -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new AssertionError("Concurrent start timed out");
                }
                try {
                    return action.call();
                } catch (BusinessException exception) {
                    return exception.getErrorCode();
                }
            })).toList();
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<Object> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private FriendRequestResponse create(User requester, User receiver) {
        return service.create(requester.getId(), new FriendRequestCreateRequest(receiver.getId()));
    }

    private long requestCount(User first, User second) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM friend_requests WHERE "
                        + "(requester_id = ? AND receiver_id = ?) OR (requester_id = ? AND receiver_id = ?)",
                Long.class, first.getId(), second.getId(), second.getId(), first.getId());
    }

    private long relationCount(User first, User second) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM friendships WHERE user_id_1 = ? AND user_id_2 = ?",
                Long.class, first.getId(), second.getId());
    }

    private User user() {
        User saved = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com",
                "$2a$10$" + "a".repeat(53), "동시성 친구", LocalDate.of(2000, 1, 1)));
        signupCreditService.grant(saved.getId());
        userIds.add(saved.getId());
        return saved;
    }

    private void assertRewardedOnce(User user) {
        assertThat(jdbc.queryForObject(
                "SELECT balance FROM credit_accounts WHERE user_id = ?",
                Long.class,
                user.getId()
        )).isEqualTo(600_000L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_transactions WHERE user_id = ? AND type = 'FRIENDSHIP_REWARD'",
                Long.class,
                user.getId()
        )).isEqualTo(1L);
    }

    @AfterEach
    void cleanUp() {
        for (Long id : userIds) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", id);
            jdbc.update("DELETE FROM credit_transactions WHERE user_id = ?", id);
            jdbc.update("DELETE FROM friend_requests WHERE requester_id = ? OR receiver_id = ?", id, id);
            jdbc.update("DELETE FROM friendships WHERE user_id_1 = ? OR user_id_2 = ?", id, id);
            jdbc.update("DELETE FROM credit_accounts WHERE user_id = ?", id);
        }
        for (Long id : userIds) {
            jdbc.update("DELETE FROM users WHERE id = ?", id);
        }
    }
}
