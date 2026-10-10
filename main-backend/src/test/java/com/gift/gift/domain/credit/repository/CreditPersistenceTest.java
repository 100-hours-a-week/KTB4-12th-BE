package com.gift.gift.domain.credit.repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.credit.entity.CreditAccount;
import com.gift.gift.domain.credit.entity.CreditTransaction;
import com.gift.gift.domain.credit.entity.CreditTransactionType;
import com.gift.gift.domain.user.entity.User;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        properties = "spring.jpa.hibernate.ddl-auto=validate"
)
@Transactional
class CreditPersistenceTest {

    private static final String PASSWORD_HASH =
            "$2a$10$" + "a".repeat(53);

    @Autowired
    private CreditAccountRepository creditAccountRepository;

    @Autowired
    private CreditTransactionRepository creditTransactionRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("크레딧 계좌를 저장하면 사용자와 잔액이 보존된다")
    void save_persistsCreditAccount() {
        User user = persistUser();

        CreditAccount saved = creditAccountRepository.saveAndFlush(new CreditAccount(user.getId(), 500_000L));

        entityManager.clear();

        CreditAccount found = creditAccountRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getUserId()).isEqualTo(user.getId());
        assertThat(found.getBalance()).isEqualTo(500_000L);
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 사용자의 크레딧 계좌는 두 개 저장할 수 없다")
    void save_rejectsDuplicateAccountForSameUser() {
        User user = persistUser();

        creditAccountRepository.saveAndFlush(new CreditAccount(user.getId(), 500_000L));

        CreditAccount duplicate = new CreditAccount(user.getId(), 500_000L);

        assertThatThrownBy(
                () -> creditAccountRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("잔액이 충분하면 크레딧이 차감된다")
    void decreaseBalance_decreasesWhenBalanceIsEnough() {
        User user = persistUser();

        creditAccountRepository.saveAndFlush(new CreditAccount(user.getId(), 100_000L));

        int updatedCount = creditAccountRepository.decreaseBalance(user.getId(), 60_000L);

        CreditAccount account = creditAccountRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(updatedCount).isEqualTo(1);
        assertThat(account.getBalance()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("잔액이 부족하면 크레딧을 차감하지 않는다")
    void decreaseBalance_doesNotDecreaseWhenBalanceIsInsufficient() {
        User user = persistUser();

        creditAccountRepository.saveAndFlush(new CreditAccount(user.getId(), 50_000L));

        int updatedCount = creditAccountRepository.decreaseBalance(user.getId(), 60_000L);

        CreditAccount account = creditAccountRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(updatedCount).isZero();
        assertThat(account.getBalance()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("양수 금액만 크레딧 잔액에 더할 수 있다")
    void increaseBalance_increasesPositiveAmount() {
        User user = persistUser();

        creditAccountRepository.saveAndFlush(new CreditAccount(user.getId(), 100_000L));

        int updatedCount = creditAccountRepository.increaseBalance(user.getId(), 30_000L);

        CreditAccount account = creditAccountRepository.findByUserId(user.getId()).orElseThrow();

        assertThat(updatedCount).isEqualTo(1);
        assertThat(account.getBalance()).isEqualTo(130_000L);
    }

    @Test
    @DisplayName("음수 크레딧 잔액은 DB CHECK 제약이 거부한다")
    void insert_rejectsNegativeBalance() {
        User user = persistUser();
        LocalDateTime now = LocalDateTime.now();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO credit_accounts (
                    user_id,
                    balance,
                    created_at,
                    updated_at
                ) VALUES (?, ?, ?, ?)
                """,
                user.getId(),
                -1L,
                Timestamp.valueOf(now),
                Timestamp.valueOf(now)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("크레딧 원장을 저장하면 거래 정보가 보존된다")
    void save_persistsCreditTransaction() {
        User user = persistUser();
        LocalDate attendanceDate = LocalDate.of(2026, 10, 10);

        CreditTransaction saved =
                creditTransactionRepository.saveAndFlush(
                        new CreditTransaction(
                                user.getId(),
                                CreditTransactionType
                                        .DAILY_ATTENDANCE_REWARD,
                                100_000L,
                                600_000L,
                                null,
                                null,
                                attendanceDate,
                                "DAILY_ATTENDANCE_REWARD:"
                                        + user.getId()
                                        + ":"
                                        + attendanceDate
                        )
                );

        entityManager.clear();

        CreditTransaction found = creditTransactionRepository.findById(saved.getId()).orElseThrow();

        assertThat(found.getUserId()).isEqualTo(user.getId());
        assertThat(found.getType()).isEqualTo(CreditTransactionType.DAILY_ATTENDANCE_REWARD);
        assertThat(found.getAmount()).isEqualTo(100_000L);
        assertThat(found.getBalanceAfter()).isEqualTo(600_000L);
        assertThat(found.getOrderRecordId()).isNull();
        assertThat(found.getGiftHistoryId()).isNull();
        assertThat(found.getAttendanceDate()).isEqualTo(attendanceDate);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("같은 중복 방지 키로 원장을 두 번 저장할 수 없다")
    void save_rejectsDuplicateDeduplicationKey() {
        User user = persistUser();
        String deduplicationKey =
                "SIGNUP_REWARD:" + user.getId();

        creditTransactionRepository.saveAndFlush(
                new CreditTransaction(
                        user.getId(),
                        CreditTransactionType.SIGNUP_REWARD,
                        500_000L,
                        500_000L,
                        null,
                        null,
                        null,
                        deduplicationKey
                )
        );

        CreditTransaction duplicate = new CreditTransaction(
                user.getId(),
                CreditTransactionType.SIGNUP_REWARD,
                500_000L,
                500_000L,
                null,
                null,
                null,
                deduplicationKey
        );

        assertThatThrownBy(
                () -> creditTransactionRepository.saveAndFlush(duplicate)
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("0 크레딧 원장은 DB CHECK 제약이 거부한다")
    void insert_rejectsZeroTransactionAmount() {
        User user = persistUser();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO credit_transactions (
                    user_id,
                    type,
                    amount,
                    balance_after,
                    order_record_id,
                    gift_history_id,
                    attendance_date,
                    deduplication_key,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(),
                CreditTransactionType.SIGNUP_REWARD.name(),
                0L,
                500_000L,
                null,
                null,
                null,
                "INVALID_AMOUNT:" + UUID.randomUUID(),
                Timestamp.valueOf(LocalDateTime.now())
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("출석 일자는 DATE 타입으로 저장된다")
    void save_preservesAttendanceDate() {
        User user = persistUser();
        LocalDate attendanceDate = LocalDate.of(2026, 10, 10);

        jdbcTemplate.update(
                """
                INSERT INTO credit_transactions (
                    user_id,
                    type,
                    amount,
                    balance_after,
                    attendance_date,
                    deduplication_key,
                    created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                user.getId(),
                CreditTransactionType
                        .DAILY_ATTENDANCE_REWARD
                        .name(),
                100_000L,
                100_000L,
                Date.valueOf(attendanceDate),
                "ATTENDANCE_DATE:" + user.getId(),
                Timestamp.valueOf(LocalDateTime.now())
        );

        LocalDate savedDate = jdbcTemplate.queryForObject(
                """
                SELECT attendance_date
                FROM credit_transactions
                WHERE deduplication_key = ?
                """,
                LocalDate.class,
                "ATTENDANCE_DATE:" + user.getId()
        );

        assertThat(savedDate).isEqualTo(attendanceDate);
    }

    private User persistUser() {
        String suffix = UUID.randomUUID().toString();

        User user = new User(
                "credit-" + suffix + "@example.com",
                PASSWORD_HASH,
                "김크레딧",
                LocalDate.of(2000, 1, 1)
        );

        entityManager.persist(user);
        entityManager.flush();

        return user;
    }
}
