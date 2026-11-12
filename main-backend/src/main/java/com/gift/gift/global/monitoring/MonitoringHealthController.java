package com.gift.gift.global.monitoring;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 관리 포트 분리 시에도 기존 애플리케이션 포트의 Health 계약만 유지한다. */
@RestController
@Profile("monitoring")
@ConditionalOnProperty(prefix = "monitoring.health-bridge", name = "enabled", havingValue = "true")
public class MonitoringHealthController {

    private final HealthEndpoint healthEndpoint;
    private final HealthEndpointGroups healthGroups;

    public MonitoringHealthController(HealthEndpoint healthEndpoint, HealthEndpointGroups healthGroups) {
        this.healthEndpoint = healthEndpoint;
        this.healthGroups = healthGroups;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, Object>> health() {
        HealthDescriptor health = healthEndpoint.health();
        return ResponseEntity.status(healthGroups.getPrimary().getHttpCodeStatusMapper().getStatusCode(health.getStatus()))
                .body(Map.of("status", health.getStatus().getCode(), "groups", healthGroups.getNames()));
    }

    @GetMapping("/actuator/health/liveness")
    public ResponseEntity<Map<String, Object>> liveness() {
        return groupHealth("liveness");
    }

    @GetMapping("/actuator/health/readiness")
    public ResponseEntity<Map<String, Object>> readiness() {
        return groupHealth("readiness");
    }

    private ResponseEntity<Map<String, Object>> groupHealth(String name) {
        HealthEndpointGroup group = healthGroups.get(name);
        HealthDescriptor health = healthEndpoint.healthForPath(name);
        if (group == null || health == null) {
            return ResponseEntity.notFound().build();
        }
        // DOWN/OUT_OF_SERVICE도 원래 Health 그룹의 HTTP 코드로 전달한다.
        // 상세 컴포넌트·DB 주소 등은 애플리케이션 포트에 노출하지 않는다.
        return ResponseEntity.status(group.getHttpCodeStatusMapper().getStatusCode(health.getStatus()))
                .body(Map.of("status", health.getStatus().getCode()));
    }
}
