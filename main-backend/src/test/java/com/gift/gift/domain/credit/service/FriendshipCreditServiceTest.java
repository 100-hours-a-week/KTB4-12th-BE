package com.gift.gift.domain.credit.service;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.credit.repository.CreditAccountRepository;
import com.gift.gift.domain.credit.repository.CreditTransactionRepository;
import com.gift.gift.domain.friend.entity.Friendship;
import com.gift.gift.domain.user.entity.User;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FriendshipCreditServiceTest {

    @Mock
    private CreditAccountRepository accountRepository;

    @Mock
    private CreditTransactionRepository transactionRepository;

    @Captor
    private ArgumentCaptor<CreditTransaction> transactionCaptor;

    private FriendshipCreditService service;

    @BeforeEach
    void setUp() {
        service = new FriendshipCreditService(
                accountRepository,
                transactionRepository
        );
    }

    @Test
    @DisplayName("신규 친구 관계의 두 사용자에게 각각 100000크레딧을 지급한다")
    void grant_rewardsBothUsers() {
        Friendship friendship = friendship(30L, 10L, 20L);

        when(accountRepository.increaseBalance(10L, 100_000L)).thenReturn(1);

        when(accountRepository.increaseBalance(20L, 100_000L)).thenReturn(1);

        when(accountRepository.findByUserId(10L))
                .thenReturn(java.util.Optional.of(
                        new CreditAccount(10L, 600_000L)));

        when(accountRepository.findByUserId(20L))
                .thenReturn(java.util.Optional.of(
                        new CreditAccount(20L, 600_000L)));

        service.grant(friendship);

        verify(transactionRepository, times(2))
                .saveAndFlush(transactionCaptor.capture());

        List<CreditTransaction> transactions = transactionCaptor.getAllValues();

        CreditTransaction first = transactions.get(0);
        CreditTransaction second = transactions.get(1);

        assertReward(first, 10L, "FRIENDSHIP_REWARD:30:10");

        assertReward(second, 20L, "FRIENDSHIP_REWARD:30:20");
    }

    @Test
    @DisplayName("한 사용자의 크레딧 계좌를 변경할 수 없으면 지급을 중단한다")
    void grant_failsWhenAccountCannotBeUpdated() {
        Friendship friendship = friendship(30L, 10L, 20L);

        when(accountRepository.increaseBalance(10L, 100_000L)).thenReturn(0);

        assertThrows(
                IllegalStateException.class,
                () -> service.grant(friendship)
        );
    }

    @Test
    @DisplayName("저장되지 않은 친구 관계에는 크레딧을 지급하지 않는다")
    void grant_rejectsUnsavedFriendship() {
        Friendship friendship = friendship(
                null,
                10L,
                20L
        );

        assertThrows(
                IllegalStateException.class,
                () -> service.grant(friendship)
        );
    }

    private void assertReward(CreditTransaction transaction, Long userId, String deduplicationKey) {

        assertEquals(userId, transaction.getUserId());

        assertEquals(CreditTransactionType.FRIENDSHIP_REWARD, transaction.getType());

        assertEquals(100_000L, transaction.getAmount());

        assertEquals(600_000L,
                transaction.getBalanceAfter()
        );

        assertEquals(deduplicationKey, transaction.getDeduplicationKey());

        assertNull(transaction.getOrderRecordId());
        assertNull(transaction.getGiftHistoryId());
        assertNull(transaction.getAttendanceDate());
    }

    private Friendship friendship(Long friendshipId, Long firstUserId, Long secondUserId) {

        User first = user(firstUserId, "first@example.com");

        User second = user(secondUserId, "second@example.com");

        Friendship friendship =
                new Friendship(first, second);

        ReflectionTestUtils.setField(friendship, "id", friendshipId);

        return friendship;
    }

    private User user(Long userId, String email) {

        User user = new User(
                email,
                "$2a$10$" + "a".repeat(53),
                "친구사용자",
                LocalDate.of(2000, 1, 1)
        );

        ReflectionTestUtils.setField(user, "id", userId);

        return user;
    }
}
