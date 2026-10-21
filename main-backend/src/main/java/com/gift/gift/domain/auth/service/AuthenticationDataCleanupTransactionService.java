package com.gift.gift.domain.auth.service;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.repository.UserSessionRepository;

@Service
@RequiredArgsConstructor
public class AuthenticationDataCleanupTransactionService {

    private final UserSessionRepository userSessionRepository;

    private final LoginIpRateLimitRepository
            loginIpRateLimitRepository;

    private final LoginEmailFailureLimitRepository
            loginEmailFailureLimitRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteSessionBatch(
            LocalDateTime inactiveBefore,
            int batchSize
    ) {
        return userSessionRepository.deleteInactiveBatch(
                inactiveBefore,
                batchSize
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthenticationDataCleanupBatchResult deleteIpRateLimitBatch(
            LocalDateTime inactiveBefore,
            int batchSize
    ) {
        List<String> candidates = loginIpRateLimitRepository
                .findInactiveCandidates(inactiveBefore, batchSize)
                .stream()
                .sorted()
                .toList();

        int deletedCount = 0;

        for (String identifierHash : candidates) {
            deletedCount += loginIpRateLimitRepository
                    .deleteInactiveByIdentifierHash(
                            identifierHash,
                            inactiveBefore
                    );
        }

        return new AuthenticationDataCleanupBatchResult(
                candidates.size(),
                deletedCount
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuthenticationDataCleanupBatchResult deleteEmailFailureLimitBatch(
            LocalDateTime inactiveBefore,
            LocalDateTime now,
            int batchSize
    ) {
        List<String> candidates = loginEmailFailureLimitRepository
                .findInactiveCandidates(inactiveBefore, now, batchSize)
                .stream()
                .sorted()
                .toList();

        int deletedCount = 0;

        for (String identifierHash : candidates) {
            deletedCount += loginEmailFailureLimitRepository
                    .deleteInactiveByIdentifierHash(
                            identifierHash,
                            inactiveBefore,
                            now
                    );
        }

        return new AuthenticationDataCleanupBatchResult(
                candidates.size(),
                deletedCount
        );
    }
}
