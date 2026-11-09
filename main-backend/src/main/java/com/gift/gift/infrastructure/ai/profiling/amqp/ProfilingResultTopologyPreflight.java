package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.Assert;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import tools.jackson.databind.JsonNode;

import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration.*;

public class ProfilingResultTopologyPreflight {

    private final RestClient client;
    private final URI managementUrl;
    private final String vhost;
    private final String amqpUsername;

    public ProfilingResultTopologyPreflight(
            RestClient client,
            URI managementUrl,
            String vhost,
            String amqpUsername
    ) {
        this.client = client;
        this.managementUrl = managementUrl;
        this.vhost = vhost;
        this.amqpUsername = amqpUsername;
    }

    public void verify() {
        JsonNode exchange = get("exchanges", vhost, EXCHANGE);

        require(
                "direct".equals(exchange.path("type").asText())
                        && exchange.path("durable").asBoolean()
                        && !exchange.path("auto_delete").asBoolean()
                        && !exchange.path("internal").asBoolean(),
                "Exchange 구성이 다릅니다."
        );

        JsonNode source = queue(RESULT_QUEUE);
        JsonNode resultFailed = queue(RESULT_FAILED_QUEUE);
        JsonNode requestFailed = queue(REQUEST_FAILED_QUEUE);

        JsonNode policy = source.path("effective_policy_definition");

        require(
                EXCHANGE.equals(
                        policy.path("dead-letter-exchange").asText()
                ),
                "결과 DLX가 다릅니다."
        );

        require(
                RESULT_FAILED_QUEUE.equals(
                        policy.path("dead-letter-routing-key").asText()
                ),
                "결과 DLQ Routing Key가 다릅니다."
        );

        require(
                "at-least-once".equals(
                        policy.path("dead-letter-strategy").asText()
                ),
                "at-least-once DLX가 필요합니다."
        );

        requireRejectPublish(source);
        requireRejectPublish(resultFailed);
        requireRejectPublish(requestFailed);

        for (JsonNode failed : new JsonNode[]{
                resultFailed,
                requestFailed
        }) {
            JsonNode failedPolicy =
                    failed.path("effective_policy_definition");

            require(
                    !failedPolicy.has("dead-letter-exchange")
                            && !failedPolicy.has("dead-letter-routing-key"),
                    "실패 큐에 추가 DLX를 설정하지 않습니다."
            );
        }

        binding(RESULT_QUEUE);
        binding(RESULT_FAILED_QUEUE);
        binding(REQUEST_FAILED_QUEUE);

        featureFlag("quorum_queue");
        featureFlag("stream_queue");

        verifyPermissions();
    }

    private JsonNode queue(String name) {
        JsonNode queue = get("queues", vhost, name);

        require(
                "quorum".equals(queue.path("type").asText())
                        && queue.path("durable").asBoolean()
                        && !queue.path("auto_delete").asBoolean()
                        && !queue.path("exclusive").asBoolean()
                        && "running".equals(queue.path("state").asText()),
                "사용 가능한 durable Quorum 큐가 필요합니다: " + name
        );

        JsonNode arguments = queue.path("arguments");

        for (String key : Set.of(
                "x-message-ttl",
                "x-expires",
                "x-dead-letter-exchange",
                "x-dead-letter-routing-key",
                "x-dead-letter-strategy",
                "x-overflow"
        )) {
            require(
                    !arguments.has(key),
                    "Policy를 덮어쓰는 큐 인자가 있습니다: " + key
            );
        }

        JsonNode policy = queue.path("effective_policy_definition");

        require(
                !policy.has("message-ttl")
                        && !policy.has("expires"),
                "TTL 또는 큐 자동 만료를 설정하지 않습니다."
        );

        return queue;
    }

    private void requireRejectPublish(JsonNode queue) {
        require(
                "reject-publish".equals(
                        queue.path("effective_policy_definition")
                                .path("overflow")
                                .asText()
                ),
                "overflow=reject-publish가 필요합니다."
        );
    }

    private void binding(String queueName) {
        JsonNode bindings = get(
                "bindings", vhost,
                "e", EXCHANGE,
                "q", queueName
        );

        require(bindings.isArray(), "Binding 응답이 배열이 아닙니다.");

        Set<String> routingKeys = new HashSet<>();

        for (JsonNode binding : bindings) {
            require(
                    binding.path("arguments").isEmpty(),
                    "추가 Binding 인자를 허용하지 않습니다."
            );

            routingKeys.add(
                    binding.path("routing_key").asText()
            );
        }

        require(
                routingKeys.equals(Set.of(queueName)),
                "Binding이 결과 계약과 다릅니다: " + queueName
        );
    }

    private void featureFlag(String name) {
        JsonNode flags = get("feature-flags");
        require(flags.isArray(), "Feature Flag 응답이 없습니다.");

        boolean enabled = false;

        for (JsonNode flag : flags) {
            if (name.equals(flag.path("name").asText())) {
                enabled = "enabled".equals(
                        flag.path("state").asText()
                );
                break;
            }
        }

        require(enabled, "필수 Feature Flag가 활성화되지 않았습니다: " + name);
    }

    private void verifyPermissions() {
        JsonNode permissions = get(
                "permissions",
                vhost,
                amqpUsername
        );

        permission(permissions, "configure", EXCHANGE);

        for (String queueName : Set.of(
                REQUEST_QUEUE,
                RESULT_QUEUE,
                REQUEST_FAILED_QUEUE,
                RESULT_FAILED_QUEUE
        )) {
            permission(permissions, "configure", queueName);
            permission(permissions, "write", queueName);
        }

        permission(permissions, "read", EXCHANGE);
        permission(permissions, "read", RESULT_QUEUE);
        permission(permissions, "write", EXCHANGE);
    }

    private void permission(
            JsonNode permissions,
            String operation,
            String resource
    ) {
        String expression =
                permissions.path(operation).asText();

        require(
                !expression.isEmpty()
                        && Pattern.compile(expression)
                        .matcher(resource)
                        .find(),
                "AMQP 계정 권한이 부족합니다: " + operation
        );
    }

    private JsonNode get(String... segments) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUri(managementUrl)
                .pathSegment("api")
                .pathSegment(segments);

        // 신규 큐의 통계 갱신 전에 Policy가 누락되지 않도록 실제 큐 메타데이터를 조회한다.
        if ("queues".equals(segments[0])) {
            builder.queryParam("disable_stats", true);
        }

        URI uri = builder
                .build()
                .encode()
                .toUri();

        JsonNode response = client.get()
                .uri(uri)
                .retrieve()
                .body(JsonNode.class);

        Assert.notNull(
                response,
                "RabbitMQ Management 응답이 없습니다."
        );

        return response;
    }

    private void require(boolean condition, String message) {
        Assert.state(condition, message);
    }
}
