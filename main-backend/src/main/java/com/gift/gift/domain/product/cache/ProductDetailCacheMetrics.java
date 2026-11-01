package com.gift.gift.domain.product.cache;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class ProductDetailCacheMetrics {

    public enum Stage {
        STOCK_DB("stock_db"), CACHE_READ("cache_read"),
        METADATA_DB("metadata_db"), CACHE_WRITE("cache_write"),
        RESPONSE_BUILD("response_build"), VIEW_HISTORY("view_history");

        private final String tag;

        Stage(String tag) {
            this.tag = tag;
        }
    }

    private final Map<Stage, Timer> stages = new EnumMap<>(Stage.class);
    private final Map<String, Counter> reads;
    private final Map<String, Counter> writes;

    public ProductDetailCacheMetrics(MeterRegistry registry) {
        for (Stage stage : Stage.values()) {
            stages.put(stage, Timer.builder("gift.product.detail.stage")
                    .description("Product detail application stage time, including mapping and waits")
                    .tag("stage", stage.tag)
                    .publishPercentileHistogram()
                    .minimumExpectedValue(Duration.ofNanos(1_000))
                    .maximumExpectedValue(Duration.ofSeconds(30))
                    .register(registry));
        }
        reads = Map.of(
                "hit", readCounter(registry, "hit"),
                "miss", readCounter(registry, "miss"),
                "error", readCounter(registry, "error")
        );
        writes = Map.of(
                "success", writeCounter(registry, "success"),
                "error", writeCounter(registry, "error")
        );
        Gauge.builder("gift.product.detail.instrumentation.ready", () -> 1)
                .description("Product detail cache instrumentation is registered")
                .register(registry);
    }

    public <T> T time(Stage stage, Supplier<T> action) {
        return stages.get(stage).record(action);
    }

    public void read(String result) {
        reads.get(result).increment();
    }

    public void write(String result) {
        writes.get(result).increment();
    }

    private Counter readCounter(MeterRegistry registry, String result) {
        return Counter.builder("gift.product.detail.cache.requests")
                .description("Cache lookups: hit, logical miss, or Redis read error")
                .tag("result", result).register(registry);
    }

    private Counter writeCounter(MeterRegistry registry, String result) {
        return Counter.builder("gift.product.detail.cache.writes")
                .tag("result", result).register(registry);
    }
}
