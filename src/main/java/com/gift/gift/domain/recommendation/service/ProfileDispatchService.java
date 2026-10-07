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
import com.gift.gift.domain.recommendation.exception.AiProfilingClientException;
import com.gift.gift.domain.recommendation.repository.RecipientProfileRepository;
import com.gift.gift.domain.recommendation.service.ProfileDispatchTransactionService.FailedDispatchResult;
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
                // PR 5-2에서 복구 루프를 추가해도 이 return을 유지한다.
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

        // HTTP 및 클라이언트 처리만 이 예외 처리 범위에 포함한다.
        try {
            response = aiProfilingClient.requestProfiling(
                    dispatch.request()
            );
        } catch (AiProfilingClientException exception) {
            // 실패 반영의 DB 오류는 전파된다.
            // 트랜잭션이 커밋된 뒤에만 처리 결과를 기록한다.
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

            // 일반 실패 재시작 대상과 틱 중단 기준은 구분한다.
            return !exception.isRetryable();
        }

        // DB 오류를 HTTP 실패로 취급하거나 삼키지 않는다.
        transactionService.applyAcceptedResponse(dispatch, response);
        return true;
    }
}
