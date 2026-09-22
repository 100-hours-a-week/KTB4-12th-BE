package com.gift.gift.domain.user.service;

import java.time.LocalDateTime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.gift.gift.domain.user.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.user.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.user.repository.UserSessionRepository;

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
    public int deleteIpRateLimitBatch(
            LocalDateTime inactiveBefore,
            int batchSize
    ) {
        return loginIpRateLimitRepository.deleteInactiveBatch(
                inactiveBefore,
                batchSize
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteEmailFailureLimitBatch(
            LocalDateTime inactiveBefore,
            LocalDateTime now,
            int batchSize
    ) {
        return loginEmailFailureLimitRepository
                .deleteInactiveBatch(
                        inactiveBefore,
                        now,
                        batchSize
                );
    }
}
