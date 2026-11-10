package com.gift.gift.domain.order.recovery;

import java.io.IOException;

import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskRepository.DispatchJob;
import com.gift.gift.domain.order.service.PaymentCancellationReconciliationService;
import com.gift.gift.domain.order.service.PaymentReconciliationService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnExpression(
        "${app.payment.mq.topology.enabled:false} and "
                + "${app.payment.recovery.dispatch.enabled:false}"
)
public class PaymentRecoveryWorker {

    private final ObjectMapper objectMapper;
    private final PaymentRecoveryTaskService taskService;
    private final PaymentRecoveryDispatchProperties properties;
    private final PaymentReconciliationService approvalService;
    private final PaymentCancellationReconciliationService cancellationService;

    public void consume(
            Message message,
            Channel channel,
            PaymentRecoveryTaskType expectedType
    ) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        PaymentRecoveryMessage recoveryMessage;

        try {
            recoveryMessage = objectMapper.readValue(
                    message.getBody(),
                    PaymentRecoveryMessage.class
            );
        } catch (RuntimeException exception) {
            log.error(
                    "결제 복구 메시지를 읽을 수 없어 격리합니다. queueType={}, errorType={}",
                    expectedType,
                    exception.getClass().getSimpleName()
            );
            channel.basicReject(deliveryTag, false);
            return;
        }

        if (taskService.isWrongTargetType(
                recoveryMessage.taskId(),
                expectedType
        )) {
            log.error(
                    "결제 복구 메시지의 대상 유형이 큐와 일치하지 않아 격리합니다. "
                            + "taskId={}, queueType={}",
                    recoveryMessage.taskId(),
                    expectedType
            );
            channel.basicReject(deliveryTag, false);
            return;
        }

        DispatchJob job = taskService.claimWorker(
                recoveryMessage.taskId(),
                expectedType,
                recoveryMessage.generation(),
                properties.leaseSeconds()
        );

        if (job == null) {
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            if (job.targetType() == PaymentRecoveryTaskType.APPROVAL) {
                approvalService.reconcileTask(job.targetId(), job.claimToken());
            } else {
                cancellationService.reconcileTask(
                        job.targetId(),
                        job.claimToken()
                );
            }

            channel.basicAck(deliveryTag, false);
        } catch (RuntimeException exception) {
            try {
                taskService.retryWorkerDelivery(job);
            } catch (RuntimeException updateException) {
                log.error(
                        "결제 복구 재전달 상태를 저장하지 못했습니다. "
                                + "taskId={}, errorType={}",
                        job.id(),
                        updateException.getClass().getSimpleName()
                );
            }

            log.error(
                    "결제 복구 Worker 실행에 실패했습니다. taskId={}, generation={}, errorType={}",
                    job.id(),
                    job.generation(),
                    exception.getClass().getSimpleName(),
                    exception
            );
            channel.basicReject(deliveryTag, true);
        }
    }
}
