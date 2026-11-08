package com.gift.gift.global.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.gift.gift.global.messaging.MessageEnvelope;
import com.gift.gift.global.messaging.RabbitPublisher;
import com.gift.gift.global.messaging.RabbitRoutingProperties;
import com.gift.gift.global.outbox.OutboxClaimRepository.Job;

import static org.mockito.Mockito.*;

class OutboxRelayTest {

    private final OutboxClaimRepository repository = mock(OutboxClaimRepository.class);
    private final RabbitPublisher publisher = mock(RabbitPublisher.class);
    private final OutboxRelay relay = new OutboxRelay(
            repository,
            publisher,
            new OutboxProperties(5, 60, 5000, 10, 2, 300),
            new RabbitRoutingProperties("test.events", "test.search")
    );

    private Job job(long id) {
        return new Job(
                id,
                "token",
                new MessageEnvelope(
                        "event-" + id,
                        "product.search.sync.v1",
                        1,
                        "key-" + id,
                        "GIFT",
                        "42",
                        null,
                        new ObjectMapper().createObjectNode(),
                        LocalDateTime.now(),
                        null
                )
        );
    }

    @Test
    @DisplayName("발행 확인 전에는 완료하지 않으며 확인 실패만 발행 재시도로 기록한다")
    void onlyCompletesConfirmedPublishes() {
        Job success = job(1);
        Job failure = job(2);
        when(repository.claim("product.search.sync.v1", 5)).thenReturn(List.of(success, failure));

        var pending = new CompletableFuture<RabbitPublisher.Result>();
        when(publisher.publish(
                success.envelope(),
                "token",
                "test.events",
                "test.search"
        )).thenReturn(pending);

        when(publisher.publish(
                failure.envelope(),
                "token",
                "test.events",
                "test.search"
        )).thenAnswer(call -> {
            verify(repository, never()).complete(success);
            pending.complete(new RabbitPublisher.Result(true, null));
            return CompletableFuture.completedFuture(
                    new RabbitPublisher.Result(false, "ConfirmTimeout")
            );
        });
        relay.relaySearch();
        verify(repository).complete(success);
        verify(repository).fail(failure, "ConfirmTimeout");
        verify(repository, never()).complete(failure);
    }

    @Test
    @DisplayName("발행 성공 후 DB 갱신이 실패해도 다음 결과를 처리하고 임대 복구에 맡긴다")
    void leavesUnknownDatabaseOutcomeForLeaseRecovery() {
        Job first = job(1);
        Job second = job(2);
        when(repository.claim("product.search.sync.v1", 5)).thenReturn(List.of(first, second));
        when(publisher.publish(
                first.envelope(),
                "token",
                "test.events",
                "test.search"
        )).thenReturn(CompletableFuture.completedFuture(new RabbitPublisher.Result(true, null)));
        when(publisher.publish(
                second.envelope(),
                "token",
                "test.events",
                "test.search"
        )).thenReturn(CompletableFuture.completedFuture(
                new RabbitPublisher.Result(true, null)
        ));
        when(repository.complete(first)).thenThrow(new IllegalStateException("DB unavailable"));
        relay.relaySearch();
        verify(repository).complete(second);
        verify(repository, never()).fail(eq(first), anyString());
    }
}
