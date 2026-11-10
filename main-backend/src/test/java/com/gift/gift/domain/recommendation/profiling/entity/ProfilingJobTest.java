package com.gift.gift.domain.recommendation.profiling.entity;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import com.gift.gift.domain.user.entity.User;
import com.gift.gift.domain.recommendation.profiling.dto.request.*;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingMessageSerializer;

import static org.assertj.core.api.Assertions.*;

class ProfilingJobTest {
    private final ProfilingMessageSerializer serializer = new ProfilingMessageSerializer(new ObjectMapper());

    @ParameterizedTest
    @EnumSource(value = ProfilingResultStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("최종 결과 기록은 발행 선점·예약을 정리하고 오류와 시도 이력을 구분한다")
    void finalResultClearsPublicationClaimAndPreservesHistory(ProfilingResultStatus finalStatus) {
        var job = ProfilingJob.create(profile(), message(1, 1, null), serializer);
        var finished = LocalDateTime.of(2030, 11, 9, 9, 0);
        var published = finished.minusHours(9);
        ReflectionTestUtils.setField(job, "publishStatus", ProfilingPublishStatus.CLAIMED);
        ReflectionTestUtils.setField(job, "claimToken", UUID.randomUUID().toString());
        ReflectionTestUtils.setField(job, "claimedAt", published.minusSeconds(1));
        ReflectionTestUtils.setField(job, "nextAttemptAt", published.plusSeconds(1));
        ReflectionTestUtils.setField(job, "attemptCount", 2);
        ReflectionTestUtils.setField(job, "firstFailedAt", published.minusMinutes(1));
        ReflectionTestUtils.setField(job, "lastErrorCode", "PUBLISH_NACK");

        assertThat(job.finishFromResult(finalStatus, "a".repeat(64), finished, published)).isTrue();
        assertThat(job.getPublishStatus()).isEqualTo(ProfilingPublishStatus.PUBLISHED);
        assertThat(job.getResultStatus()).isEqualTo(finalStatus);
        assertThat(job.getResultFingerprint()).isEqualTo("a".repeat(64));
        assertThat(job.getFinishedAt()).isEqualTo(finished);
        assertThat(job.getPublishedAt()).isEqualTo(published);
        assertThat(job.getAttemptCount()).isEqualTo(2);
        assertThat(job.getFirstFailedAt()).isEqualTo(published.minusMinutes(1));
        assertThat(job.getClaimToken()).isNull();
        assertThat(job.getClaimedAt()).isNull();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastErrorCode()).isEqualTo(
                finalStatus == ProfilingResultStatus.FAILED ? "AI_RESULT_FAILED" : null);
    }

    @Test
    @DisplayName("이미 확인된 발행 시각은 보존하고 종료 작업의 해시를 다시 쓸 수 없다")
    void preservesPublishedTimeAndRejectsRepeatedTermination() {
        var job = ProfilingJob.create(profile(), message(1, 1, null), serializer);
        var published = LocalDateTime.of(2026, 11, 9, 0, 0);
        ReflectionTestUtils.setField(job, "publishedAt", published);
        assertThat(job.finishFromResult(ProfilingResultStatus.COMPLETED, "a".repeat(64),
                published.plusDays(1), published.plusHours(1))).isFalse();
        assertThat(job.getPublishedAt()).isEqualTo(published);
        assertThatThrownBy(() -> job.finishFromResult(ProfilingResultStatus.FAILED, "b".repeat(64),
                published.plusDays(2), published.plusHours(2))).isInstanceOf(IllegalStateException.class);
        assertThat(job.getResultStatus()).isEqualTo(ProfilingResultStatus.COMPLETED);
        assertThat(job.getResultFingerprint()).isEqualTo("a".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    @DisplayName("잘못된 해시는 상태를 변경하기 전에 거부한다")
    void rejectsInvalidResultFingerprintWithoutMutation(String invalidHash) {
        var job = ProfilingJob.create(profile(), message(1, 1, null), serializer);
        assertThatThrownBy(() -> job.finishFromResult(ProfilingResultStatus.COMPLETED,
                invalidHash, LocalDateTime.now(), LocalDateTime.now())).isInstanceOf(IllegalArgumentException.class);
        assertThat(job.getResultStatus()).isEqualTo(ProfilingResultStatus.WAITING);
        assertThat(job.getResultFingerprint()).isNull();
        assertThat(job.getPublishStatus()).isEqualTo(ProfilingPublishStatus.READY);
    }

    private RecipientProfile profile() {
        User user = new User("job@example.com", "hash", "테스트", LocalDate.of(1990, 1, 1));
        ReflectionTestUtils.setField(user, "id", 1L);
        RecipientProfile profile = new RecipientProfile(user);
        ReflectionTestUtils.setField(profile, "id", 10L);
        profile.createNextSourceVersion();
        return profile;
    }

    private ProfilingRequestMessage message(long userId, long version, String text) {
        return ProfilingRequestMessage.create(UUID.randomUUID(), Instant.now(),
                new AiProfileRequest(userId, version, List.of(), text, List.of()));
    }

    @Test
    @DisplayName("수신자 또는 원본 버전이 프로필과 다르면 작업 생성을 거부한다")
    void rejectsRecipientOrVersionMismatch() {
        assertThatThrownBy(() -> ProfilingJob.create(profile(), message(2, 1, null), serializer))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProfilingJob.create(profile(), message(1, 2, null), serializer))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반환된 바이트 배열을 변경해도 저장된 스냅샷은 변경되지 않는다")
    void returnedBytesCannotMutateStoredSnapshot() {
        var job = ProfilingJob.create(profile(), message(1, 1, "원본 입력"), serializer);
        String original = job.getRequestBody();
        byte[] bytes = job.requestBodyBytes();
        bytes[0] = 0;
        assertThat(job.getRequestBody()).isEqualTo(original);
        assertThat(job.requestBodyBytes()[0]).isEqualTo((byte) '{');
    }

    @Test
    @DisplayName("대체 처리 시 차단 작업의 입력과 오류를 보존하고 발행된 작업은 변경하지 않는다")
    void supersessionPreservesBlockedInputAndErrorAndDoesNotTouchPublished() {
        var job = ProfilingJob.create(profile(), message(1, 1, "한".repeat(90_000)), serializer);
        String body = job.getRequestBody();
        job.supersedeBlocked(LocalDateTime.now());
        assertThat(job.getResultStatus()).isEqualTo(ProfilingResultStatus.SUPERSEDED);
        assertThat(job.getPublishStatus()).isEqualTo(ProfilingPublishStatus.BLOCKED);
        assertThat(job.getRequestBody()).isEqualTo(body);
        assertThat(job.getLastErrorCode()).isEqualTo("INPUT_TOO_LARGE");
        var published = ProfilingJob.create(profile(), message(1, 1, null), serializer);
        ReflectionTestUtils.setField(published, "publishStatus", ProfilingPublishStatus.PUBLISHED);
        published.supersedeBlocked(LocalDateTime.now());
        assertThat(published.getResultStatus()).isEqualTo(ProfilingResultStatus.WAITING);
        assertThat(published.getFinishedAt()).isNull();
    }

    @Test
    @DisplayName("동일 입력 재개는 요청 식별자와 본문을 유지하고 발행 예산을 초기화한다")
    void resumesBlockedJobWithoutChangingSnapshot() {
        ProfilingJob job = ProfilingJob.create(
                profile(),
                message(1, 1, "원본 입력"),
                serializer
        );

        String requestId = job.getRequestId();
        String body = job.getRequestBody();

        ReflectionTestUtils.setField(
                job, "publishStatus", ProfilingPublishStatus.BLOCKED
        );
        ReflectionTestUtils.setField(job, "attemptCount", 3);
        ReflectionTestUtils.setField(
                job, "firstFailedAt", LocalDateTime.now()
        );
        ReflectionTestUtils.setField(
                job, "lastErrorCode", "PUBLISH_BUDGET_EXHAUSTED"
        );

        job.resumeBlockedPublication();

        assertThat(job.getRequestId()).isEqualTo(requestId);
        assertThat(job.getSourceVersion()).isEqualTo(1);
        assertThat(job.getRequestBody()).isEqualTo(body);
        assertThat(job.getPublishStatus())
                .isEqualTo(ProfilingPublishStatus.READY);
        assertThat(job.getResultStatus())
                .isEqualTo(ProfilingResultStatus.WAITING);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getFirstFailedAt()).isNull();
        assertThat(job.getNextAttemptAt()).isNull();
        assertThat(job.getLastErrorCode()).isNull();
    }

    @Test
    @DisplayName("선점 정보가 남아 있는 차단 작업은 재개하지 않는다")
    void rejectsBlockedJobWithClaim() {
        var job = ProfilingJob.create(
                profile(), message(1, 1, null), serializer
        );

        ReflectionTestUtils.setField(
                job, "publishStatus", ProfilingPublishStatus.BLOCKED
        );
        ReflectionTestUtils.setField(
                job, "claimToken", UUID.randomUUID().toString()
        );
        ReflectionTestUtils.setField(
                job, "claimedAt", LocalDateTime.now()
        );

        assertThatThrownBy(job::resumeBlockedPublication)
                .isInstanceOf(IllegalStateException.class);

        assertThat(job.getPublishStatus())
                .isEqualTo(ProfilingPublishStatus.BLOCKED);
    }

    @Test
    @DisplayName("크기 초과 본문은 동일 입력으로 재개하지 않는다")
    void rejectsResumingOversizedSnapshot() {
        var job = ProfilingJob.create(
                profile(),
                message(1, 1, "한".repeat(90_000)),
                serializer
        );

        assertThatThrownBy(job::resumeBlockedPublication)
                .isInstanceOf(IllegalStateException.class);

        assertThat(job.getPublishStatus())
                .isEqualTo(ProfilingPublishStatus.BLOCKED);
    }
}
