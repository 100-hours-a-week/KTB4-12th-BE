package com.gift.gift.domain.bugreport.messaging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.gift.gift.global.outbox.OutboxRelay;

import static org.mockito.Mockito.*;

class ReportOutboxRelayTest {
    private final OutboxRelay relay = mock(OutboxRelay.class);
    private final ReportTopologyPreflight preflight = mock(ReportTopologyPreflight.class);
    private final ReportOutboxRelay reports = new ReportOutboxRelay(relay, preflight,
            new ReportMqProperties(10));

    @Test
    @DisplayName("전역 스케줄링 없이 신고 발행 루프가 동작하고 종료된다")
    void runsWithoutGlobalScheduling() throws Exception {
        try {
            reports.afterPropertiesSet();
            verify(relay, timeout(4000)).relay("report.created.v1", 10, "report.events", "Report.created");
        } finally {
            reports.destroy();
        }
        int afterShutdown = mockingDetails(relay).getInvocations().size();
        Thread.sleep(1200);
        org.junit.jupiter.api.Assertions.assertEquals(afterShutdown, mockingDetails(relay).getInvocations().size());
    }

    @Test
    @DisplayName("두 채널 토폴로지를 확인한 뒤에만 리포트 타입과 전용 Exchange로 발행한다")
    void verifiesBeforeClaimingReportBatch() {
        reports.relayReports();
        var order = inOrder(preflight, relay);
        order.verify(preflight).verify();
        order.verify(relay).relay("report.created.v1", 10, "report.events", "Report.created");
        verifyNoMoreInteractions(relay);
    }

    @Test
    @DisplayName("관리 API 또는 토폴로지 장애에서는 선점과 실패 횟수 갱신을 하지 않는다")
    void doesNotClaimWhenPreflightFails() {
        doThrow(new IllegalStateException("missing binding")).when(preflight).verify();
        reports.relayReports();
        verifyNoInteractions(relay);
    }
}
