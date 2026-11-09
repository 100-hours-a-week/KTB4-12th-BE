package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.io.IOException;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.dao.DataAccessException;

import com.gift.gift.domain.recommendation.profiling.dto.request.ProfilingResultMessage;
import com.gift.gift.domain.recommendation.profiling.service.ProfilingResultService;
import com.gift.gift.domain.recommendation.profiling.support.ProfilingResultMessageReader;

@Slf4j
@RequiredArgsConstructor
public class ProfilingResultMessageListener implements ChannelAwareMessageListener {

    private final ProfilingResultMessageReader reader;
    private final ProfilingResultService service;
    private final Consumer<String> halt;
    private final BooleanSupplier halted;

    @Override
    public void onMessage(Message message, Channel channel) {
        if (halted.getAsBoolean()) {
            return;
        }

        if (channel == null) {
            halt.accept("CHANNEL_MISSING");
            return;
        }

        long deliveryTag = message
                .getMessageProperties()
                .getDeliveryTag();

        try {
            final ProfilingResultMessage resultMessage;

            try {
                resultMessage = reader.read(message.getBody());

                var properties = message.getMessageProperties();

                if (!Objects.equals(
                        resultMessage.requestId(),
                        properties.getMessageId()
                ) || !Objects.equals(
                        resultMessage.requestId(),
                        properties.getCorrelationId()
                )) {
                    throw new IllegalArgumentException(
                            "AMQP 식별자와 requestId가 다릅니다."
                    );
                }
            } catch (IllegalArgumentException exception) {
                log.warn(
                        "PROFILING_RESULT_INVALID deliveryTag={}",
                        deliveryTag
                );

                settle(channel, deliveryTag, false);
                return;
            }

            // 프록시 호출이 정상 반환될 때 DB 커밋이 완료된다.
            var result = service.save(resultMessage);

            switch (result.decision()) {
                case RECORDED, DUPLICATE, IGNORED ->
                        settle(channel, deliveryTag, true);

                case QUARANTINE -> {
                    log.warn(
                            "PROFILING_RESULT_QUARANTINE "
                                    + "requestId={} reason={}",
                            resultMessage.requestId(),
                            result.reason()
                    );

                    settle(channel, deliveryTag, false);
                }
            }
        } catch (DataAccessException exception) {
            log.error(
                    "PROFILING_RESULT_DB_ERROR "
                            + "deliveryTag={} exceptionType={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );

            halt.accept("DB_ERROR");
        } catch (RuntimeException exception) {
            log.error(
                    "PROFILING_RESULT_UNCLASSIFIED "
                            + "deliveryTag={} exceptionType={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );

            halt.accept("UNCLASSIFIED");
        }
    }

    private void settle(
            Channel channel,
            long deliveryTag,
            boolean accepted
    ) {
        try {
            if (accepted) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicReject(deliveryTag, false);
            }
        } catch (IOException | RuntimeException exception) {
            log.error(
                    "PROFILING_RESULT_SETTLEMENT_ERROR "
                            + "deliveryTag={} exceptionType={}",
                    deliveryTag,
                    exception.getClass().getSimpleName()
            );

            halt.accept("SETTLEMENT_ERROR");
        }
    }
}
