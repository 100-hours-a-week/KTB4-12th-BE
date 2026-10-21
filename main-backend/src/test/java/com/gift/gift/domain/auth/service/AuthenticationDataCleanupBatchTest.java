package com.gift.gift.domain.auth.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.gift.gift.domain.auth.repository.LoginEmailFailureLimitRepository;
import com.gift.gift.domain.auth.repository.LoginIpRateLimitRepository;
import com.gift.gift.domain.auth.repository.UserSessionRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticationDataCleanupBatchTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 4, 0);
    private static final LocalDateTime CUTOFF = NOW.minusHours(24);

    @Mock
    private UserSessionRepository sessions;
    @Mock
    private LoginIpRateLimitRepository ips;
    @Mock
    private LoginEmailFailureLimitRepository emails;

    private AuthenticationDataCleanupTransactionService service;

    @BeforeEach
    void setUp() {
        service = new AuthenticationDataCleanupTransactionService(sessions, ips, emails);
    }

    @Test
    @DisplayName("IP 후보는 PK 순서로 삭제하고 조회 건수와 실제 삭제 건수를 분리한다")
    void ipBatch_sortsKeysAndCountsActualDeletes() {
        when(ips.findInactiveCandidates(CUTOFF, 1_000)).thenReturn(List.of("c", "a", "b"));
        when(ips.deleteInactiveByIdentifierHash("a", CUTOFF)).thenReturn(1);
        when(ips.deleteInactiveByIdentifierHash("b", CUTOFF)).thenReturn(0);
        when(ips.deleteInactiveByIdentifierHash("c", CUTOFF)).thenReturn(1);

        assertThat(service.deleteIpRateLimitBatch(CUTOFF, 1_000))
                .isEqualTo(new AuthenticationDataCleanupBatchResult(3, 2));

        var order = inOrder(ips);
        order.verify(ips).findInactiveCandidates(CUTOFF, 1_000);
        order.verify(ips).deleteInactiveByIdentifierHash("a", CUTOFF);
        order.verify(ips).deleteInactiveByIdentifierHash("b", CUTOFF);
        order.verify(ips).deleteInactiveByIdentifierHash("c", CUTOFF);
    }

    @Test
    @DisplayName("이메일 후보도 PK 순서로 삭제하며 동일한 기준 시각을 전달한다")
    void emailBatch_sortsKeysAndPreservesCutoffs() {
        when(emails.findInactiveCandidates(CUTOFF, NOW, 1_000)).thenReturn(List.of("b", "a"));
        when(emails.deleteInactiveByIdentifierHash("a", CUTOFF, NOW)).thenReturn(0);
        when(emails.deleteInactiveByIdentifierHash("b", CUTOFF, NOW)).thenReturn(1);

        assertThat(service.deleteEmailFailureLimitBatch(CUTOFF, NOW, 1_000))
                .isEqualTo(new AuthenticationDataCleanupBatchResult(2, 1));

        var order = inOrder(emails);
        order.verify(emails).findInactiveCandidates(CUTOFF, NOW, 1_000);
        order.verify(emails).deleteInactiveByIdentifierHash("a", CUTOFF, NOW);
        order.verify(emails).deleteInactiveByIdentifierHash("b", CUTOFF, NOW);
    }

    @Test
    @DisplayName("빈 후보는 삭제 쿼리를 호출하지 않는다")
    void emptyCandidates_doNotDelete() {
        when(ips.findInactiveCandidates(CUTOFF, 1_000)).thenReturn(List.of());
        when(emails.findInactiveCandidates(CUTOFF, NOW, 1_000)).thenReturn(List.of());

        assertThat(service.deleteIpRateLimitBatch(CUTOFF, 1_000))
                .isEqualTo(new AuthenticationDataCleanupBatchResult(0, 0));
        assertThat(service.deleteEmailFailureLimitBatch(CUTOFF, NOW, 1_000))
                .isEqualTo(new AuthenticationDataCleanupBatchResult(0, 0));
        verify(ips).findInactiveCandidates(CUTOFF, 1_000);
        verify(emails).findInactiveCandidates(CUTOFF, NOW, 1_000);
        org.mockito.Mockito.verifyNoMoreInteractions(ips, emails);
    }

    @Test
    @DisplayName("삭제 중 예외는 삼키지 않고 전파하여 배치 롤백이 가능하게 한다")
    void deleteFailure_propagatesWithoutContinuing() {
        when(ips.findInactiveCandidates(CUTOFF, 1_000)).thenReturn(List.of("a", "b"));
        IllegalStateException failure = new IllegalStateException("delete failed");
        when(ips.deleteInactiveByIdentifierHash("a", CUTOFF)).thenThrow(failure);

        assertThatThrownBy(() -> service.deleteIpRateLimitBatch(CUTOFF, 1_000))
                .isSameAs(failure);
        verify(ips, never()).deleteInactiveByIdentifierHash("b", CUTOFF);
    }
}
