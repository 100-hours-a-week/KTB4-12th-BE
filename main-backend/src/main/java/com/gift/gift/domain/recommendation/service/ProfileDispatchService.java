package com.gift.gift.domain.recommendation.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.gift.gift.domain.recommendation.dto.response.AiProfileAcceptedResponse;
import com.gift.gift.domain.recommendation.entity.RecipientProfileStatus;
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.service.ProfileDispatchTransactionService.FailedDispatchResult;
import com.gift.gift.domain.recommendation.service.ProfileDispatchTransactionService.RecoveryDispatchResult;
import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.support.PreparedProfileDispatch;
import com.gift.gift.domain.recommendation.support.PreparedRecoveryDispatch;
import com.gift.gift.infrastructure.ai.AiProfilingClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDispatchService {

    private final RecipientProfileRepository recipientProfileRepository;
    private final ProfileDispatchTransactionService transactionService;
    private final AiProfilingClient aiProfilingClient;
    private final AiProfilingProperties properties;
    private final Clock clock;

    // HTTP 호출은 DB 트랜잭션 밖에서 수행한다.
    public void dispatchDueProfiles() {
        if (!aiProfilingClient.isHealthy()) {
            log.warn("AI 프로파일링 상태 비정상으로 틱을 건너뜁니다.");
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> profileIds =
                recipientProfileRepository.findDispatchCandidateIds(
                        now.minus(properties.quietPeriod()),
                        now.minus(properties.maximumWindow()),
                        PageRequest.of(0, properties.batchSize())
                );

        for (Long profileId : profileIds) {
            if (!dispatchOne(profileId)) {
                return;
            }
        }

        if (properties.recoveryBatchSize() == 0) {
            return;
        }

        LocalDateTime recoveryCutoff = LocalDateTime.now(clock)
                .minus(properties.maximumWindow());

        List<Long> recoveryIds =
                recipientProfileRepository.findRecoveryCandidateIds(
                        RecipientProfileStatus.PENDING,
                        recoveryCutoff,
                        PageRequest.of(0, properties.recoveryBatchSize())
                );

        for (Long profileId : recoveryIds) {
            if (!recoverOne(profileId)) {
                return;
            }
        }
    }

    // true: 다음 후보 진행, false: 이번 틱 종료
    private boolean dispatchOne(Long profileId) {
        Optional<PreparedProfileDispatch> prepared =
                transactionService.prepareDispatch(profileId);

        if (prepared.isEmpty()) {
            return true;
        }

        PreparedProfileDispatch dispatch = prepared.get();
        AiProfileAcceptedResponse response;

        try {
            response = aiProfilingClient.requestProfiling(
                    dispatch.request()
            );
        } catch (AiProfilingClientException exception) {
            FailedDispatchResult result =
                    transactionService.applyFailedDispatch(dispatch);

            log.warn(
                    "AI 프로파일링 요청 실패. "
                            + "recipientUserId={}, sourceVersion={}, "
                            + "failureType={}, action={}",
                    dispatch.recipientUserId(),
                    dispatch.sourceVersion(),
                    exception.getFailureType(),
                    result
            );

            return !exception.isRetryable();
        }

        transactionService.applyAcceptedResponse(dispatch, response);
        return true;
    }

    private boolean recoverOne(Long profileId) {
        Optional<PreparedRecoveryDispatch> prepared = transactionService.prepareRecovery(profileId);

        if (prepared.isEmpty()) {
            return true;
        }

        PreparedRecoveryDispatch dispatch = prepared.get();

        try {
            // 기존 클라이언트가 202와 응답 수신자·번호·상태를 검증한다.
            aiProfilingClient.requestProfiling(dispatch.request());
        } catch (AiProfilingClientException exception) {
            RecoveryDispatchResult result =
                    transactionService.applyRecoveryResult(dispatch);

            log.warn(
                    "AI 프로파일링 복구 요청 실패. "
                            + "recipientUserId={}, sourceVersion={}, "
                            + "failureType={}, action={}",
                    dispatch.recipientUserId(),
                    dispatch.sourceVersion(),
                    exception.getFailureType(),
                    result
            );

            return !exception.isRetryable();
        }

        RecoveryDispatchResult result = transactionService.applyRecoveryResult(dispatch);

        log.info(
                "AI 프로파일링 복구 요청 접수. "
                        + "recipientUserId={}, sourceVersion={}, action={}",
                dispatch.recipientUserId(),
                dispatch.sourceVersion(),
                result
        );

        return true;
    }
}
