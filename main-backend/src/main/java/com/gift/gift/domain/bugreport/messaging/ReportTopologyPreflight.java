package com.gift.gift.domain.bugreport.messaging;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import static com.gift.gift.domain.bugreport.messaging.ReportMqProperties.*;

public class ReportTopologyPreflight {
    private final RestClient client;
    private final URI managementUrl;
    private final String vhost;

    public ReportTopologyPreflight(RestClient client, URI managementUrl, String vhost) {
        this.client = client;
        this.managementUrl = managementUrl;
        this.vhost = vhost;
    }

    public void verify() {
        for (String exchange : Set.of(EVENTS, RETRY, DLX)) {
            JsonNode resource = get("exchanges", vhost, exchange);
            require(resource.path("type").asText().equals("topic") && resource.path("durable").asBoolean()
                    && !resource.path("auto_delete").asBoolean() && !resource.path("internal").asBoolean());
        }
        queue(DISCORD_QUEUE);
        queue(SHEETS_QUEUE);
        JsonNode retry = queue(RETRY_QUEUE);
        JsonNode retryPolicy = retry.path("effective_policy_definition");
        require(retryPolicy.path("message-ttl").asLong() == 30000
                && retryPolicy.path("dead-letter-exchange").asText().equals(EVENTS)
                && !retryPolicy.has("dead-letter-routing-key"));
        JsonNode dlq = queue(DLQ);
        require(dlq.path("effective_policy_definition").path("message-ttl").asLong() == 604800000);
        bindings(EVENTS, DISCORD_QUEUE, Set.of(CREATED, DISCORD));
        bindings(EVENTS, SHEETS_QUEUE, Set.of(CREATED, SHEETS));
        bindings(RETRY, RETRY_QUEUE, Set.of(DISCORD, SHEETS));
        bindings(DLX, DLQ, Set.of("Report.#"));
    }

    private JsonNode queue(String name) {
        JsonNode queue = get("queues", vhost, name);
        require(queue.path("type").asText().equals("classic") && queue.path("durable").asBoolean()
                && !queue.path("auto_delete").asBoolean() && !queue.path("exclusive").asBoolean());
        JsonNode args = queue.path("arguments");
        for (String key : Set.of("x-message-ttl", "x-dead-letter-exchange", "x-dead-letter-routing-key", "x-overflow",
                "x-expires")) {
            require(!args.has(key));
        }
        require(queue.path("effective_policy_definition").path("overflow").asText().equals("reject-publish"));
        JsonNode policy = queue.path("effective_policy_definition");
        require(!policy.has("expires"));
        if (!name.equals(RETRY_QUEUE)) {
            require(!policy.has("dead-letter-exchange") && !policy.has("dead-letter-routing-key"));
        }
        if (name.equals(DISCORD_QUEUE) || name.equals(SHEETS_QUEUE)) {
            require(!policy.has("message-ttl"));
        }
        return queue;
    }

    private void bindings(String exchange, String queue, Set<String> expected) {
        JsonNode bindings = get("bindings", vhost, "e", exchange, "q", queue);
        require(bindings.isArray());
        Set<String> actual = new HashSet<>();
        for (JsonNode binding : bindings) {
            require(binding.path("arguments").isEmpty());
            actual.add(binding.path("routing_key").asText());
        }
        require(actual.equals(expected));
    }

    private JsonNode get(String... segments) {
        URI uri = UriComponentsBuilder.fromUri(managementUrl).pathSegment("api")
                .pathSegment(segments).build().encode().toUri();
        JsonNode response = client.get().uri(uri).retrieve().body(JsonNode.class);
        Assert.notNull(response, "리포트 토폴로지 응답이 없습니다.");
        return response;
    }

    private void require(boolean condition) {
        Assert.state(condition, "리포트 토폴로지가 확정된 큐·binding·policy 계약과 다릅니다.");
    }
}
