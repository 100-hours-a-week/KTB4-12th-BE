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
import com.gift.gift.domain.recommendation.support.AiProfilingProperties;
import com.gift.gift.domain.recommendation.support.PreparedProfileDispatch;
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

    /*
     * 의도적으로 @Transactional을 붙이지 않는다.
     * AI HTTP 호출이 DB 트랜잭션 밖에서 실행돼야 한다.
     */
    public void dispatchDueProfiles() {
        LocalDateTime now = LocalDateTime.now(clock);

        List<Long> profileIds =
                recipientProfileRepository.findDispatchCandidateIds(
                        RecipientProfileStatus.PENDING,
                        now.minus(properties.quietPeriod()),
                        now.minus(properties.maximumWindow()),
                        PageRequest.of(0, properties.batchSize())
                );

        for (Long profileId : profileIds) {
            dispatchOne(profileId);
        }
    }

    private void dispatchOne(Long profileId) {
        Optional<PreparedProfileDispatch> prepared =
                transactionService.prepareDispatch(profileId);

        if (prepared.isEmpty()) {
            return;
        }

        PreparedProfileDispatch dispatch = prepared.get();

        try {
            /*
             * prepareDispatch 트랜잭션이 이미 종료된 뒤 실행된다.
             */
            AiProfileAcceptedResponse response =
                    aiProfilingClient.requestProfiling(
                            dispatch.request()
                    );

            /*
             * 202 반영은 새로운 트랜잭션이다.
             */
            transactionService.applyAcceptedResponse(
                    dispatch,
                    response
            );
        } catch (AiProfilingClientException exception) {
            logAiFailure(dispatch, exception);
        }
    }

    private void logAiFailure(
            PreparedProfileDispatch dispatch,
            AiProfilingClientException exception
    ) {
        if (exception.isRetryable()) {
            log.warn(
                    "AI 프로파일링 요청에 재시도 가능한 오류가 발생했습니다. "
                            + "recipientUserId={}, sourceVersion={}, type={}",
                    dispatch.recipientUserId(),
                    dispatch.sourceVersion(),
                    exception.getFailureType(),
                    exception
            );
            return;
        }

        log.error(
                "AI 프로파일링 요청에 재시도할 수 없는 오류가 발생했습니다. "
                        + "recipientUserId={}, sourceVersion={}, type={}",
                dispatch.recipientUserId(),
                dispatch.sourceVersion(),
                exception.getFailureType(),
                exception
        );
    }
}
