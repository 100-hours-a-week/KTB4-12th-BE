package com.gift.gift.domain.bugreport.messaging;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

class ReplayRequestTest {
    private Map<String, String> environment() {
        return new HashMap<>(Map.of("REPLAY_TARGET", "NOTIFICATION", "REPLAY_IDS", "1,2",
            "REPLAY_LIMIT", "10", "REPLAY_OPERATOR", "ops_01"));
    }

    @Test
    @DisplayName("실행 플래그가 없으면 dry-run이며 범위·작업자 입력은 필수다")
    void defaultIsDryRunAndScopeIsRequired() {
        assertThat(ReplayRequest.parse(environment()).execute()).isFalse();
        for (String key : environment().keySet()) {
            var env = environment();
            env.remove(key);
            assertThatThrownBy(() -> ReplayRequest.parse(env)).isInstanceOf(IllegalArgumentException.class);
        }
        var env = environment();
        env.put("REPLAY_EXECUTE", "true");
        assertThat(ReplayRequest.parse(env).execute()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1001", "1"})
    @DisplayName("0·상한초과·대상수보다 작은 건수 제한을 거부한다")
    void rejectsUnsafeLimits(String limit) {
        var env = environment();
        env.put("REPLAY_LIMIT", limit);
        assertThatThrownBy(() -> ReplayRequest.parse(env)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("오타 실행 플래그·전체선택·중복 ID·감사 로그 삽입을 거부한다")
    void rejectsAmbiguousOrUnsafeInputs() {
        for (var replacement : Map.of("REPLAY_EXECUTE", "TRUE", "REPLAY_IDS", "*",
                "REPLAY_OPERATOR", "ops\nresult=OK", "REPLAY_TARGET", "ALL").entrySet()) {
            var env = environment();
            env.put(replacement.getKey(), replacement.getValue());
            assertThatThrownBy(() -> ReplayRequest.parse(env)).isInstanceOf(IllegalArgumentException.class);
        }
        var env = environment();
        env.put("REPLAY_IDS", "1,1");
        assertThatThrownBy(() -> ReplayRequest.parse(env)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReplayRequest(ReplayRequest.Target.DISCORD, Set.of("1"), 10, false, "ops"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CLI는 모든 스케줄러·소비자를 중지하고 리포트일 때만 MQ를 활성화한다")
    void cliDisablesBackgroundProcessing() {
        assertThat(OutboxReplayCommand.startupArguments(true)).contains("--app.scheduling.enabled=false",
            "--app.ai-profile.amqp.enabled=false", "--app.report.discord.enabled=false",
            "--app.report.sheets.enabled=false", "--spring.rabbitmq.dynamic=false", "--app.outbox.enabled=true");
        assertThat(OutboxReplayCommand.startupArguments(false)).contains("--app.outbox.enabled=false",
            "--app.report.mq.enabled=false");
    }
}
