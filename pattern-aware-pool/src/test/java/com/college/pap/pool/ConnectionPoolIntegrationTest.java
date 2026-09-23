package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionPoolIntegrationTest {

    @Test
    void predictiveModeFailsoverDuringBadWindow() throws Exception {
        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(10, 0).toInstant(ZoneOffset.UTC));

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock));

        PoolConfig config = new PoolConfig();
        config.setAnalyzerPeriodSeconds(3600);
        config.setPreWarmPeriodSeconds(3600);

        try (ConnectionPool pool = ConnectionPool.predictive(registry, connectors, config, clock)) {
            for (int h = 0; h < 24; h++) {
                for (int i = 0; i < 6; i++) {
                    clock.set(day.atTime(h, i * 9).toInstant(ZoneOffset.UTC));
                    try (PapConnection ignored = pool.getConnection()) {
                        // learn
                    } catch (Exception ignored) {
                        // expected
                    }
                }
            }
            pool.analyzer().analyzeAll();

            clock.set(day.atTime(13, 50).toInstant(ZoneOffset.UTC));
            pool.preWarmer().tick();

            clock.set(day.atTime(14, 20).toInstant(ZoneOffset.UTC));
            int backupSelected = 0;
            for (int i = 0; i < 20; i++) {
                try (PapConnection c = pool.getConnection()) {
                    if (backup.equals(c.endpointId())) {
                        backupSelected++;
                    }
                } catch (Exception ignored) {
                    // rare
                }
            }

            assertTrue(backupSelected >= 10,
                    "expected frequent preemptive use of backup during bad window, got " + backupSelected);
            assertTrue(pool.metrics().successRate() > 0.7);
        }
    }
}
