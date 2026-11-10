package com.gift.gift.domain.bugreport.messaging;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ReplayRequest(Target target, Set<String> ids, int limit, boolean execute, String operator) {
    public enum Target { DISCORD, SHEETS, NOTIFICATION, LEGACY_NOTIFICATION }

    public ReplayRequest {
        if (target == null || ids == null || ids.isEmpty() || limit < 1 || limit > 1000 || ids.size() > limit
                || operator == null || !operator.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("재처리 범위와 작업자 식별자를 확인하세요.");
        }
        var normalized = new LinkedHashSet<String>();
        for (String id : ids) {
            if (target == Target.DISCORD || target == Target.SHEETS) {
                String uuid = UUID.fromString(id).toString();
                if (!uuid.equals(id)) throw new IllegalArgumentException("정규 UUID가 필요합니다.");
            } else if (!id.matches("[1-9][0-9]{0,18}") || Long.parseLong(id) <= 0) {
                throw new IllegalArgumentException("양수 작업 ID가 필요합니다.");
            }
            normalized.add(id);
        }
        ids = java.util.Collections.unmodifiableSet(normalized);
    }

    public static ReplayRequest parse(Map<String, String> environment) {
        String execute = environment.getOrDefault("REPLAY_EXECUTE", "false");
        if (!execute.equals("true") && !execute.equals("false")) {
            throw new IllegalArgumentException("REPLAY_EXECUTE는 true 또는 false여야 합니다.");
        }
        var ids = new LinkedHashSet<String>();
        for (String id : required(environment, "REPLAY_IDS").split(",", -1)) {
            if (!ids.add(id.trim())) throw new IllegalArgumentException("중복 ID는 허용하지 않습니다.");
        }
        return new ReplayRequest(Target.valueOf(required(environment, "REPLAY_TARGET")), ids,
            Integer.parseInt(required(environment, "REPLAY_LIMIT")), Boolean.parseBoolean(execute),
            required(environment, "REPLAY_OPERATOR"));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("필수 설정이 없습니다: " + name);
        return value;
    }
}
