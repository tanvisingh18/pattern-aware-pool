package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Idle-pool reuse must record 0 ms connect latency so reuse-on mean latency
 * is strictly below reuse-off (which pays the connector's simulated connect latency).
 */
class ReuseLatencyTest {

    @Test
    void reuseOnMeanLatencyLessThanReuseOff() throws Exception {
        Instant start = Instant.parse("2026-07-08T10:00:00Z");
        double off = meanLatency(false, start);
        double on = meanLatency(true, start);
        assertTrue(on < off,
                () -> "expected reuse-on mean latency (" + on + ") < reuse-off (" + off + ")");
        assertTrue(on < 5.0,
                () -> "reuse-on mean should be near 0 after first physical connect, was " + on);
    }

    private static double meanLatency(boolean reuse, Instant start) throws Exception {
        MutableClock clock = MutableClock.utc(start);
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);
        Random pr = new Random(42);
        Random br = new Random(43);
        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, pr));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, br));
        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setReuseEnabled(reuse);
        config.setWarmPoolSize(1);
        try (ConnectionPool pool = ConnectionPool.reactiveBaseline(registry, connectors, config, clock)) {
            pool.metrics().reset();
            for (int i = 0; i < 200; i++) {
                clock.set(start.plusSeconds(i));
                try (PapConnection ignored = pool.getConnection()) {
                    // checkout
                }
            }
            return pool.metrics().averageLatencyMs();
        }
    }
}
