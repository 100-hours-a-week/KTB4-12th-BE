package com.gift.gift.infrastructure.ai.profiling.amqp;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static com.gift.gift.infrastructure.ai.profiling.amqp.ProfilingAmqpConfiguration.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProfilingResultTopologyPreflightTest {

    private static final String BASE = "http://localhost:15672";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final Map<String, JsonNode> resources = resources();

    @Test
    @DisplayName("실제 적용 Policy·Quorum·Binding·Feature Flag·AMQP 계정 권한을 확인한다")
    void acceptsRequiredTopology() {
        var builder = RestClient.builder();
        var server = responses(builder);
        new ProfilingResultTopologyPreflight(builder.build(), URI.create(BASE), "gift", "worker").verify();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exchange", "sourceType", "sourceDown", "strategy", "overflow", "dlx", "route",
            "ttl", "expires", "argumentTtl", "argumentDlx", "argumentOverflow", "targetTtl", "targetOverflow",
            "targetDlx", "missingQueue", "missingBinding", "extraBinding", "bindingArguments", "feature",
            "configurePermission", "writePermission", "readPermission", "invalidRegex"})
    @DisplayName("Policy·인자·Binding·기능·권한이 잘못되면 시작 검증을 실패시킨다")
    void rejectsUnsafeTopology(String fault) {
        ObjectNode source = object("queues/gift/" + RESULT_QUEUE);
        ObjectNode policy = (ObjectNode) source.path("effective_policy_definition");
        ObjectNode arguments = (ObjectNode) source.path("arguments");
        ObjectNode targetPolicy = (ObjectNode) object("queues/gift/" + RESULT_FAILED_QUEUE)
                .path("effective_policy_definition");
        ObjectNode permissions = object("permissions/gift/worker");
        switch (fault) {
            case "exchange" -> object("exchanges/gift/" + EXCHANGE).put("type", "topic");
            case "sourceType" -> source.put("type", "classic");
            case "sourceDown" -> source.put("state", "down");
            case "strategy" -> policy.remove("dead-letter-strategy");
            case "overflow" -> policy.put("overflow", "drop-head");
            case "dlx" -> policy.put("dead-letter-exchange", "other");
            case "route" -> policy.put("dead-letter-routing-key", "other");
            case "ttl" -> policy.put("message-ttl", 1);
            case "expires" -> policy.put("expires", 1);
            case "argumentTtl" -> arguments.put("x-message-ttl", 1);
            case "argumentDlx" -> arguments.put("x-dead-letter-exchange", "other");
            case "argumentOverflow" -> arguments.put("x-overflow", "drop-head");
            case "targetTtl" -> targetPolicy.put("message-ttl", 1);
            case "targetOverflow" -> targetPolicy.put("overflow", "drop-head");
            case "targetDlx" -> targetPolicy.put("dead-letter-exchange", EXCHANGE);
            case "missingQueue" -> resources.put("queues/gift/" + RESULT_FAILED_QUEUE, null);
            case "missingBinding" -> resources.put(bindingPath(RESULT_FAILED_QUEUE), mapper.createArrayNode());
            case "extraBinding" -> ((ArrayNode) resources.get(bindingPath(RESULT_FAILED_QUEUE)))
                    .addObject().put("routing_key", "other").putObject("arguments");
            case "bindingArguments" -> ((ObjectNode) resources.get(bindingPath(RESULT_FAILED_QUEUE))
                    .get(0).path("arguments")).put("unexpected", true);
            case "feature" -> ((ObjectNode) resources.get("feature-flags").get(0)).put("state", "disabled");
            case "configurePermission" -> permissions.put("configure", "^$");
            case "writePermission" -> permissions.put("write", "^$");
            case "readPermission" -> permissions.put("read", "^$");
            case "invalidRegex" -> permissions.put("read", "[");
            default -> throw new AssertionError(fault);
        }
        var builder = RestClient.builder();
        responses(builder);
        assertThatThrownBy(() -> new ProfilingResultTopologyPreflight(
                builder.build(), URI.create(BASE), "gift", "worker").verify()).isInstanceOf(RuntimeException.class);
    }

    private MockRestServiceServer responses(RestClient.Builder builder) {
        var server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        resources.forEach((path, response) -> {
            String query = path.startsWith("queues/") ? "?disable_stats=true" : "";
            var request = server.expect(ExpectedCount.manyTimes(), requestTo(BASE + "/api/" + path + query));
            if (response == null) {
                request.andRespond(withStatus(HttpStatus.NOT_FOUND));
            } else {
                request.andRespond(withSuccess(response.toString(), MediaType.APPLICATION_JSON));
            }
        });
        return server;
    }

    private ObjectNode object(String path) {
        return (ObjectNode) resources.get(path);
    }

    private String bindingPath(String queue) {
        return "bindings/gift/e/" + EXCHANGE + "/q/" + queue;
    }

    private Map<String, JsonNode> resources() {
        Map<String, JsonNode> result = new HashMap<>();
        result.put("exchanges/gift/" + EXCHANGE, mapper.valueToTree(Map.of(
                "type", "direct", "durable", true, "auto_delete", false, "internal", false)));
        for (String queue : new String[]{RESULT_QUEUE, RESULT_FAILED_QUEUE, REQUEST_FAILED_QUEUE}) {
            ObjectNode resource = mapper.valueToTree(Map.of("type", "quorum", "durable", true,
                    "auto_delete", false, "exclusive", false, "state", "running"));
            resource.putObject("arguments").put("x-queue-type", "quorum");
            ObjectNode policy = resource.putObject("effective_policy_definition");
            policy.put("overflow", "reject-publish");
            if (queue.equals(RESULT_QUEUE)) {
                policy.put("dead-letter-exchange", EXCHANGE);
                policy.put("dead-letter-routing-key", RESULT_FAILED_QUEUE);
                policy.put("dead-letter-strategy", "at-least-once");
            }
            result.put("queues/gift/" + queue, resource);
            var bindings = mapper.createArrayNode();
            bindings.addObject().put("routing_key", queue).putObject("arguments");
            result.put(bindingPath(queue), bindings);
        }
        var flags = mapper.createArrayNode();
        flags.addObject().put("name", "quorum_queue").put("state", "enabled");
        flags.addObject().put("name", "stream_queue").put("state", "enabled");
        result.put("feature-flags", flags);
        result.put("permissions/gift/worker", mapper.valueToTree(Map.of("configure", ".*", "write", ".*", "read", ".*")));
        return result;
    }
}
