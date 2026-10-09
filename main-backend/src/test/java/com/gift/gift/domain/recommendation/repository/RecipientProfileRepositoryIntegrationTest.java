package com.gift.gift.domain.recommendation.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.recommendation.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.user.repository.UserRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@Transactional
class RecipientProfileRepositoryIntegrationTest {

    @Autowired
    private RecipientProfileRepository profileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("사용자 한 명당 하나의 프로파일만 저장할 수 있다")
    void save_rejectsDuplicateRecipientProfile() {
        User recipient = persistUser();
        profileRepository.saveAndFlush(new RecipientProfile(recipient));

        assertThatThrownBy(() ->
                profileRepository.saveAndFlush(
                        new RecipientProfile(recipient)
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("수신자 ID로 프로파일을 조회한다")
    void findByRecipientId_returnsProfile() {
        RecipientProfile saved = persistProfile();
        Long profileId = saved.getId();
        Long recipientId = saved.getRecipient().getId();

        entityManager.clear();

        RecipientProfile found = profileRepository
                .findByRecipient_Id(recipientId)
                .orElseThrow();

        assertThat(found.getId()).isEqualTo(profileId);
        assertThat(found.getRecipient().getId())
                .isEqualTo(recipientId);
        assertThat(profileRepository.existsByRecipient_Id(recipientId))
                .isTrue();
    }

    @Test
    @DisplayName("프로파일의 음수 버전을 DB CHECK 제약조건이 거부한다")
    void database_rejectsNegativeSourceVersion() {
        RecipientProfile profile = persistProfile();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_profiles
                SET source_version = -1
                WHERE id = ?
                """,
                profile.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("분석 완료 버전이 요청 버전보다 큰 값을 DB가 거부한다")
    void database_rejectsAnalyzedVersionGreaterThanSourceVersion() {
        RecipientProfile profile = persistProfile();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_profiles
                SET source_version = 1,
                    analyzed_source_version = 2
                WHERE id = ?
                """,
                profile.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("재시도 횟수 3을 DB CHECK 제약조건이 거부한다")
    void database_rejectsRetryCountGreaterThanTwo() {
        RecipientProfile profile = persistProfile();

        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                UPDATE recipient_profiles
                SET retry_count = 3
                WHERE id = ?
                """,
                profile.getId()
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Check constraint");
    }

    @Test
    @DisplayName("존재하지 않는 사용자의 프로파일을 FK가 거부한다")
    void database_rejectsNonexistentRecipient() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO recipient_profiles (
                    recipient_id,
                    profile_status,
                    source_version,
                    analyzed_source_version,
                    retry_count,
                    created_at,
                    updated_at
                ) VALUES (?, 'NONE', 0, 0, 0, NOW(6), NOW(6))
                """,
                Long.MAX_VALUE
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("마지막 변경 1시간 또는 변경 구간 6시간이 지난 프로파일만 전송 후보로 조회한다")
    void findDispatchCandidates_returnsDueProfilesAndExcludesPending() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 18, 0);

        RecipientProfile quietPeriodDue = persistProfile();
        quietPeriodDue.recordPreferenceChange(now.minusHours(2));

        RecipientProfile maxWindowDue = persistProfile();
        maxWindowDue.recordPreferenceChange(now.minusHours(7));
        maxWindowDue.recordPreferenceChange(now.minusMinutes(10));

        RecipientProfile notDue = persistProfile();
        notDue.recordPreferenceChange(now.minusMinutes(10));

        RecipientProfile pending = persistProfile();
        pending.recordPreferenceChange(now.minusHours(2));
        pending.createNextSourceVersion();
        pending.markPending(now.minusMinutes(5));

        profileRepository.flush();

        List<RecipientProfile> candidates =
                profileRepository.findDispatchCandidates(
                        RecipientProfileStatus.PENDING,
                        now.minusHours(1),
                        now.minusHours(6),
                        PageRequest.of(0, 100)
                );

        assertThat(candidates)
                .extracting(RecipientProfile::getId)
                .containsExactlyInAnyOrder(
                        quietPeriodDue.getId(),
                        maxWindowDue.getId()
                )
                .doesNotContain(
                        notDue.getId(),
                        pending.getId()
                );
    }

    @Test
    @DisplayName("기준 시각보다 오래 PENDING인 프로파일만 타임아웃 후보로 조회한다")
    void findPendingTimeoutCandidates_returnsExpiredPendingProfiles() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 24, 18, 0);

        RecipientProfile expired = persistPendingProfile(
                now.minusMinutes(11)
        );
        RecipientProfile active = persistPendingProfile(
                now.minusMinutes(5)
        );
        RecipientProfile none = persistProfile();

        profileRepository.flush();

        List<RecipientProfile> candidates =
                profileRepository.findPendingTimeoutCandidates(
                        RecipientProfileStatus.PENDING,
                        now.minusMinutes(10),
                        PageRequest.of(0, 100)
                );

        assertThat(candidates)
                .extracting(RecipientProfile::getId)
                .containsExactly(expired.getId())
                .doesNotContain(active.getId(), none.getId());
    }

    private RecipientProfile persistPendingProfile(
            LocalDateTime pendingSince
    ) {
        RecipientProfile profile = persistProfile();
        profile.createNextSourceVersion();
        profile.markPending(pendingSince);
        return profile;
    }

    private RecipientProfile persistProfile() {
        User recipient = persistUser();
        return profileRepository.saveAndFlush(
                new RecipientProfile(recipient)
        );
    }

    private User persistUser() {
        return userRepository.saveAndFlush(
                new User(
                        "recipient-" + UUID.randomUUID() + "@example.com",
                        "$2a$10$" + "a".repeat(53),
                        "수신자",
                        LocalDate.of(2000, 1, 1)
                )
        );
    }
}
