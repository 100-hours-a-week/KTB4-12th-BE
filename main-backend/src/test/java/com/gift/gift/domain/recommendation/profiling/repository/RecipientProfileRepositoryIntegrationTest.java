package com.gift.gift.domain.recommendation.profiling.repository;

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

import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfile;
import com.gift.gift.domain.recommendation.profiling.entity.RecipientProfileStatus;
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
    @DisplayName("존재하지 않는 사용자의 프로파일을 FK가 거부한다")
    void database_rejectsNonexistentRecipient() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                """
                INSERT INTO recipient_profiles (
                    recipient_id,
                    profile_status,
                    source_version,
                    analyzed_source_version,
                    created_at,
                    updated_at
                ) VALUES (?, 'NONE', 0, 0, NOW(6), NOW(6))
                """,
                Long.MAX_VALUE
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("디바운스가 경과한 신규 변경은 PENDING 중에도 후보로 조회한다")
    void findDispatchCandidates_returnsDueProfilesIncludingPending() {
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

        List<Long> candidates =
                profileRepository.findJobPreparationCandidateIds(
                        now.minusHours(1),
                        now.minusHours(6),
                        PageRequest.of(0, 100)
                );

        assertThat(candidates)
                .containsExactlyInAnyOrder(
                        quietPeriodDue.getId(),
                        maxWindowDue.getId(),
                        pending.getId()
                )
                .doesNotContain(notDue.getId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(
            com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus.class)
    @DisplayName("현재 작업의 미확정 발행만 새 작업 후보에서 제외하고 PUBLISHED·BLOCKED는 허용한다")
    void filtersOnlyUnconfirmedCurrentPublication(
            com.gift.gift.domain.recommendation.profiling.entity.ProfilingPublishStatus publishStatus) {
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);
        RecipientProfile profile = persistPendingProfile(now.minusHours(2));
        profile.recordPreferenceChange(now.minusHours(1));
        profileRepository.flush();
        jdbcTemplate.update("""
                INSERT INTO profiling_jobs (request_id, profile_id, source_version, request_body,
                    publish_status, result_status, created_at, updated_at)
                VALUES (?, ?, 1, '{}', ?, 'WAITING', NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), profile.getId(), publishStatus.name());
        List<Long> candidates = profileRepository.findJobPreparationCandidateIds(
                now.minusMinutes(1), now.minusMinutes(5), PageRequest.of(0, 100));
        boolean blockedByPublication = java.util.Set.of("READY", "CLAIMED", "RETRY_WAIT")
                .contains(publishStatus.name());
        assertThat(candidates.contains(profile.getId())).isEqualTo(!blockedByPublication);
    }

    @Test
    @DisplayName("미확정 작업은 페이지 한도를 차지하지 않아 다음 프로필 준비가 굶지 않는다")
    void excludesUnconfirmedJobsBeforeApplyingPageLimit() {
        // 공유 DB의 다른 후보는 이 테스트 트랜잭션에서만 제외하고 종료 시 롤백한다.
        jdbcTemplate.update("UPDATE recipient_profiles SET last_changed_at = NULL, window_started_at = NULL");
        LocalDateTime now = LocalDateTime.of(2026, 11, 6, 12, 0);
        RecipientProfile waiting = persistPendingProfile(now.minusHours(3));
        waiting.recordPreferenceChange(now.minusHours(2));
        RecipientProfile eligible = persistProfile();
        eligible.recordPreferenceChange(now.minusHours(1));
        profileRepository.flush();
        jdbcTemplate.update("""
                INSERT INTO profiling_jobs (request_id, profile_id, source_version, request_body,
                    publish_status, result_status, created_at, updated_at)
                VALUES (?, ?, 1, '{}', 'READY', 'WAITING', NOW(6), NOW(6))
                """, UUID.randomUUID().toString(), waiting.getId());
        assertThat(profileRepository.findJobPreparationCandidateIds(
                now.minusMinutes(1), now.minusMinutes(5), PageRequest.of(0, 1)))
                .containsExactly(eligible.getId());
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
