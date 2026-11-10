package com.gift.gift.domain.bugreport.messaging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import com.rabbitmq.client.Channel;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.support.DefaultMessagePropertiesConverter;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.RabbitPublisher;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;

public class ReportDlqReplay {
    private final ObjectMapper mapper;
    private final RabbitPublisher publisher;

    public ReportDlqReplay(ObjectMapper mapper, RabbitPublisher publisher) {
        this.mapper = mapper;
        this.publisher = publisher;
    }

    public boolean replay(Channel channel, ReplayRequest request, BiConsumer<String, String> audit) throws Exception {
        String channelName = switch (request.target()) {
            case DISCORD -> "discord";
            case SHEETS -> "sheets";
            default -> throw new IllegalArgumentException("리포트 대상 채널이 아닙니다.");
        };
        String queue = request.target() == ReplayRequest.Target.DISCORD ? DISCORD_QUEUE : SHEETS_QUEUE;
        String routingKey = request.target() == ReplayRequest.Target.DISCORD ? DISCORD : SHEETS;
        var remaining = new LinkedHashSet<>(request.ids());
        var held = new ArrayList<Long>();
        var converter = new DefaultMessagePropertiesConverter();
        boolean valid = true;
        try {
            for (int scanned = 0; scanned < request.limit() && !remaining.isEmpty(); scanned++) {
                var delivery = channel.basicGet(DLQ, false);
                if (delivery == null) break;
                long tag = delivery.getEnvelope().getDeliveryTag();
                held.add(tag);
                var properties = converter.toMessageProperties(delivery.getProps(), delivery.getEnvelope(), "UTF-8");
                String id = properties.getMessageId();
                if (!remaining.contains(id) || !channelName.equals(properties.getHeader("delivery.channel"))) continue;
                var message = new Message(delivery.getBody(), properties);
                try {
                    var envelope = ReportConsumer.parseEnvelope(message, mapper);
                    if (!id.equals(envelope.eventId()) || !queue.equals(
                            properties.getHeader("delivery.originalQueue"))) {
                        throw new IllegalArgumentException("DLQ 전달 메타데이터가 다릅니다.");
                    }
                } catch (RuntimeException invalid) {
                    audit.accept(id, "INVALID_PRESERVED");
                    remaining.remove(id);
                    valid = false;
                    continue;
                }
                if (!request.execute()) {
                    audit.accept(id, "DRY_RUN_ELIGIBLE");
                } else {
                    audit.accept(id, "PUBLISHING");
                    var reset = new MessageProperties();
                    reset.getHeaders().putAll(properties.getHeaders());
                    reset.setMessageId(id);
                    reset.setType(properties.getType());
                    reset.setContentType(properties.getContentType());
                    reset.setContentEncoding(properties.getContentEncoding());
                    reset.setHeader(ReportConsumer.RETRY_COUNT, 0);
                    reset.setHeader(ReportConsumer.NEXT_ATTEMPT_AT, 0L);
                    reset.getHeaders().remove("delivery.errorCode");
                    reset.getHeaders().remove("delivery.failedAt");
                    var result = publisher.publish(new Message(message.getBody(), reset), UUID.randomUUID().toString(),
                            EVENTS, routingKey).get(6, TimeUnit.SECONDS);
                    if (!result.confirmed()) throw new IOException("재발행 결과가 확인되지 않았습니다.");
                    channel.basicAck(tag, false);
                    held.remove(Long.valueOf(tag));
                    audit.accept(id, "REPLAYED");
                }
                remaining.remove(id);
            }
        } finally {
            for (Long tag : held) channel.basicNack(tag, false, true);
        }
        for (String id : remaining) audit.accept(id, "NOT_FOUND_WITHIN_LIMIT");
        return valid && remaining.isEmpty();
    }
}
