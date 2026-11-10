package com.gift.gift.domain.order.recovery;

import java.nio.charset.StandardCharsets;

import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskRepository.DispatchJob;
import com.gift.gift.domain.order.service.PaymentCancellationReconciliationService;
import com.gift.gift.domain.order.service.PaymentReconciliationService;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoveryWorkerTest {

    private final PaymentRecoveryTaskService taskService =
            mock(PaymentRecoveryTaskService.class);
    private final PaymentReconciliationService approvalService =
            mock(PaymentReconciliationService.class);
    private final PaymentCancellationReconciliationService cancellationService =
            mock(PaymentCancellationReconciliationService.class);
    private final Channel channel = mock(Channel.class);

    private final PaymentRecoveryWorker worker = new PaymentRecoveryWorker(
            new ObjectMapper(),
            taskService,
            new PaymentRecoveryDispatchProperties(100, 60, 5000),
            approvalService,
            cancellationService
    );

    @Test
    @DisplayName("현재 세대의 승인 메시지는 승인 재조정 후 ACK한다")
    void acknowledgesClaimedApprovalMessage() throws Exception {
        Message message = message(11L, 3L, 41L);
        when(taskService.isWrongTargetType(
                11L,
                PaymentRecoveryTaskType.APPROVAL
        )).thenReturn(false);
        when(taskService.claimWorker(
                11L,
                PaymentRecoveryTaskType.APPROVAL,
                3L,
                60
        )).thenReturn(new DispatchJob(
                11L,
                PaymentRecoveryTaskType.APPROVAL,
                91L,
                3L,
                "worker-token"
        ));

        worker.consume(message, channel, PaymentRecoveryTaskType.APPROVAL);

        verify(approvalService).reconcileTask(91L, "worker-token");
        verify(channel).basicAck(41L, false);
        verify(channel, never()).basicReject(41L, true);
    }

    @Test
    @DisplayName("이미 선점된 중복 메시지는 업무 호출 없이 ACK한다")
    void acknowledgesDuplicateMessageWithoutCallingPaymentService()
            throws Exception {
        Message message = message(12L, 4L, 42L);
        when(taskService.isWrongTargetType(
                12L,
                PaymentRecoveryTaskType.CANCELLATION
        )).thenReturn(false);
        when(taskService.claimWorker(
                12L,
                PaymentRecoveryTaskType.CANCELLATION,
                4L,
                60
        )).thenReturn(null);

        worker.consume(
                message,
                channel,
                PaymentRecoveryTaskType.CANCELLATION
        );

        verify(channel).basicAck(42L, false);
        verify(approvalService, never()).reconcileTask(any(), any());
        verify(cancellationService, never()).reconcileTask(any(), any());
    }

    private Message message(long taskId, long generation, long deliveryTag) {
        byte[] body = ("{\"taskId\":" + taskId
                + ",\"generation\":" + generation + "}")
                .getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(body, properties);
    }
}
