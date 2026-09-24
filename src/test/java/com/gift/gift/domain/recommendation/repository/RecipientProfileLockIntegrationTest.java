package com.gift.gift.domain.recommendation.repository;

import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
class RecipientProfileLockIntegrationTest {

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long recipientId;
    private Long profileId;

    @BeforeEach
    void setUp() {
        TransactionTemplate transaction = transactionTemplate();

        transaction.executeWithoutResult(status -> {
            User recipient = userRepository.saveAndFlush(
                    new User(
                            "lock-" + UUID.randomUUID() + "@example.com",
                            "$2a$10$" + "a".repeat(53),
                            "잠금수신자",
                            LocalDate.of(2000, 1, 1)
                    )
            );
            RecipientProfile profile = profileRepository.saveAndFlush(
                    new RecipientProfile(recipient)
            );

            recipientId = recipient.getId();
            profileId = profile.getId();
        });
    }

    @AfterEach
    void tearDown() {
        transactionTemplate().executeWithoutResult(status -> {
            if (profileId != null) {
                profileRepository.deleteById(profileId);
                profileRepository.flush();
            }
            if (recipientId != null) {
                userRepository.deleteById(recipientId);
                userRepository.flush();
            }
        });
    }

    @Test
    @DisplayName("한 트랜잭션의 비관적 쓰기 잠금이 해제될 때까지 다른 트랜잭션의 조회가 대기한다")
    void findByRecipientIdForUpdate_blocksConcurrentTransaction()
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstLockAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirstLock = new CountDownLatch(1);
        CountDownLatch secondTransactionStarted = new CountDownLatch(1);

        try {
            Future<?> firstTransaction = executor.submit(() ->
                    transactionTemplate().executeWithoutResult(status -> {
                        RecipientProfile locked = profileRepository
                                .findByRecipientIdForUpdate(recipientId)
                                .orElseThrow();

                        assertThat(locked.getId()).isEqualTo(profileId);
                        firstLockAcquired.countDown();
                        await(releaseFirstLock);
                    })
            );

            assertThat(firstLockAcquired.await(3, TimeUnit.SECONDS))
                    .isTrue();

            Future<RecipientProfile> secondTransaction = executor.submit(
                    () -> {
                        secondTransactionStarted.countDown();
                        return transactionTemplate().execute(status ->
                                profileRepository
                                        .findByRecipientIdForUpdate(recipientId)
                                        .orElseThrow()
                        );
                    }
            );

            assertThat(secondTransactionStarted.await(3, TimeUnit.SECONDS))
                    .isTrue();

            Thread.sleep(200);
            assertThat(secondTransaction).isNotDone();

            releaseFirstLock.countDown();

            firstTransaction.get(3, TimeUnit.SECONDS);
            assertThat(secondTransaction.get(3, TimeUnit.SECONDS).getId())
                    .isEqualTo(profileId);
        } finally {
            releaseFirstLock.countDown();
            executor.shutdownNow();
        }
    }

    private TransactionTemplate transactionTemplate() {
        return new TransactionTemplate(transactionManager);
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "잠금 테스트 대기 시간이 초과되었습니다."
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "잠금 테스트가 중단되었습니다.",
                    exception
            );
        }
    }
}
