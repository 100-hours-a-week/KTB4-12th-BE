package com.gift.gift.domain.order.recovery;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskRepository.DispatchJob;
import com.gift.gift.global.messaging.RabbitPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaymentRecoveryDispatchServiceTest {

    private final PaymentRecoveryTaskService taskService =
            mock(PaymentRecoveryTaskService.class);
    private final RabbitPublisher publisher = mock(RabbitPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PaymentRecoveryDispatchService service =
            new PaymentRecoveryDispatchService(
                    taskService,
                    new PaymentRecoveryDispatchProperties(100, 60, 5000),
                    publisher,
                    mapper
            );

    @Test
    @DisplayName("Confirm ACK 시 DISPATCHED 작업을 READY로 되돌리지 않는다")
    void confirmedPublishKeepsTaskDispatched() {
        DispatchJob job = new DispatchJob(
                10L, PaymentRecoveryTaskType.APPROVAL, 20L, 1L, "token"
        );
        when(taskService.claimDue(100, 60)).thenReturn(List.of(job));
        when(publisher.publish(
                any(Message.class),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(CompletableFuture.completedFuture(
                new RabbitPublisher.Result(true, null)
        ));

        service.dispatchReadyTasks();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(publisher).publish(
                messageCaptor.capture(),
                eq("10:1:token"),
                eq(PaymentRabbitTopologyConfiguration.EVENTS_EXCHANGE),
                eq(PaymentRabbitTopologyConfiguration.APPROVAL_RECOVERY_ROUTING_KEY)
        );
        Message publishedMessage = messageCaptor.getValue();
        MessageProperties properties = publishedMessage.getMessageProperties();
        PaymentRecoveryMessage body = mapper.readValue(
                publishedMessage.getBody(), PaymentRecoveryMessage.class
        );
        assertThat(body).isEqualTo(new PaymentRecoveryMessage(10L, 1L));
        assertThat(properties.getMessageId()).isEqualTo("10:1");
        verify(taskService, never()).retryDispatch(any(), anyString());
    }

    @Test
    @DisplayName("NACK와 미라우팅은 현재 발행권만 READY로 되돌린다")
    void definiteFailureRetriesDispatch() {
        DispatchJob job = new DispatchJob(
                11L, PaymentRecoveryTaskType.CANCELLATION, 21L, 2L, "claim"
        );
        when(taskService.claimDue(100, 60)).thenReturn(List.of(job));
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new RabbitPublisher.Result(false, "Unroutable")
                ));

        service.dispatchReadyTasks();

        verify(taskService).retryDispatch(job, "Unroutable");
        verify(publisher).publish(
                any(Message.class),
                eq("11:2:claim"),
                eq(PaymentRabbitTopologyConfiguration.EVENTS_EXCHANGE),
                eq(PaymentRabbitTopologyConfiguration.CANCELLATION_RECOVERY_ROUTING_KEY)
        );
    }

    @Test
    @DisplayName("Confirm timeout은 수락 여부가 불명확하므로 READY로 되돌리지 않는다")
    void confirmTimeoutLeavesLeaseForRecovery() {
        DispatchJob job = new DispatchJob(
                12L, PaymentRecoveryTaskType.APPROVAL, 22L, 3L, "claim"
        );
        when(taskService.claimDue(100, 60)).thenReturn(List.of(job));
        when(publisher.publish(any(Message.class), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(
                        new RabbitPublisher.Result(false, "ConfirmTimeout")
                ));

        service.dispatchReadyTasks();

        verify(taskService, never()).retryDispatch(any(), anyString());
    }
}
