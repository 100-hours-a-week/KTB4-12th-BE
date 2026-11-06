package com.gift.gift.domain.recommendation.profiling.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.profiling.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.profiling.support.AiProfilingProperties;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingJobPublisher;
import com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingJobPublisher.TickResult;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "app.ai-profile.amqp",
        name = "enabled",
        havingValue = "true"
)
public class ProfileDispatchService {

    private final RecipientProfileRepository profileRepository;
    private final ProfilingJobPreparationService preparationService;
    private final ProfilingJobPublisher publisher;
    private final AiProfilingProperties properties;
    private final Clock clock;

    private final ReentrantLock dispatchLock = new ReentrantLock();

    // 개별 작업 준비만 트랜잭션으로 실행하고 MQ 발행은 밖에서 수행한다.
    public void dispatchDueProfiles() {
        if (!dispatchLock.tryLock()) {
            return;
        }

        try {
            prepareDueJobs();
            publishDueJobs();
        } finally {
            dispatchLock.unlock();
        }
    }

    private void prepareDueJobs() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> profileIds =
                profileRepository.findJobPreparationCandidateIds(
                        now.minus(properties.quietPeriod()),
                        now.minus(properties.maximumWindow()),
                        PageRequest.of(0, properties.batchSize())
                );

        for (Long profileId : profileIds) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            try {
                preparationService.prepare(profileId);
            } catch (RuntimeException exception) {
                // 한 프로필의 입력·저장 오류가 다른 작업의 발행을 막지 않는다.
                log.error(
                        "프로파일링 작업 준비에 실패했습니다. profileId={}",
                        profileId,
                        exception
                );
            }
        }
    }

    private void publishDueJobs() {
        for (int count = 0; count < properties.batchSize(); count++) {
            if (Thread.currentThread().isInterrupted()) {
                return;
            }

            TickResult result = publisher.publishOne();

            if (result != TickResult.PROGRESSED) {
                return;
            }
        }
    }
}
