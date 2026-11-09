package com.gift.gift.domain.order.recovery;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.domain.order.messaging.PaymentRabbitTopologyConfiguration;
import com.gift.gift.domain.order.recovery.PaymentRecoveryTaskRepository.DispatchJob;
import com.gift.gift.global.messaging.RabbitPublisher;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.payment.recovery.dispatch.enabled",
        havingValue = "true"
)
public class PaymentRecoveryDispatchService {

    private final PaymentRecoveryTaskService taskService;
    private final PaymentRecoveryDispatchProperties properties;
    private final RabbitPublisher publisher;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${app.payment.recovery.dispatch.poll-interval-ms:5000}")
    public void dispatchReadyTasks() {
        List<DispatchJob> jobs;
        try {
            jobs = taskService.claimDue(
                    properties.batchSize(), properties.leaseSeconds()
            );
        } catch (RuntimeException exception) {
            log.error(
                    "결제 복구 작업 선점에 실패했습니다. errorType={}",
                    exception.getClass().getSimpleName(),
                    exception
            );
            return;
        }

        for (DispatchJob job : jobs) {
            CompletableFuture<RabbitPublisher.Result> attempt = publish(job);
            RabbitPublisher.Result result;
            try {
                result = attempt.join();
            } catch (RuntimeException exception) {
                // 발행이 브로커에 도달했는지 불명확하면 임대 만료로 회수한다.
                log.warn(
                        "결제 복구 발행 결과가 불명확합니다. taskId={}, errorType={}",
                        job.id(),
                        exception.getClass().getSimpleName()
                );
                continue;
            }

            if (result.confirmed()) {
                continue;
            }
            if ("ConfirmTimeout".equals(result.errorType())
                    || "ConfirmUnknown".equals(result.errorType())) {
                continue;
            }

            taskService.retryDispatch(job, result.errorType());
            log.warn(
                    "결제 복구 메시지 발행 실패: taskId={}, errorType={}",
                    job.id(), result.errorType()
            );
        }
    }

    private CompletableFuture<RabbitPublisher.Result> publish(DispatchJob job) {
        String routingKey = job.targetType() == PaymentRecoveryTaskType.APPROVAL
                ? PaymentRabbitTopologyConfiguration.APPROVAL_RECOVERY_ROUTING_KEY
                : PaymentRabbitTopologyConfiguration.CANCELLATION_RECOVERY_ROUTING_KEY;
        String messageId = job.id() + ":" + job.generation();

        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setMessageId(messageId);
        properties.setType(routingKey);

        try {
            byte[] body = objectMapper.writeValueAsBytes(
                    new PaymentRecoveryMessage(job.id(), job.generation())
            );
            return publisher.publish(
                    new Message(body, properties),
                    messageId + ":" + job.claimToken(),
                    PaymentRabbitTopologyConfiguration.EVENTS_EXCHANGE,
                    routingKey
            );
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(
                    new RabbitPublisher.Result(false, "PublishException")
            );
        }
    }
}
