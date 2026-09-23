package com.college.pap.pool;

import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecision;
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
        config.setZoneId(ZoneOffset.UTC);
        config.setAnalyzerPeriodSeconds(3600);
        config.setPreWarmPeriodSeconds(3600);
        config.setHotHourMinSamples(5);
        config.setHotHourThreshold(0.50);
        // Routing experiments: always physical-connect so flaky patterns are visible.
        config.setReuseEnabled(false);

        try (ConnectionPool pool = ConnectionPool.predictive(registry, connectors, config, clock)) {
            // Seed multi-day hour-14 failure history so hot-hour fires without relying
            // on live connects during the learning loop (which would itself failover).
            for (int d = 0; d < 5; d++) {
                LocalDate hist = day.minusDays(5 - d);
                for (int i = 0; i < 6; i++) {
                    pool.historyStore().record(ConnectionAttempt.failure(
                            primary,
                            hist.atTime(14, i * 5).toInstant(ZoneOffset.UTC),
                            AttemptOutcome.TIMEOUT,
                            FailureType.TIMEOUT,
                            80));
                    pool.historyStore().record(ConnectionAttempt.success(
                            backup,
                            hist.atTime(14, i * 5).toInstant(ZoneOffset.UTC),
                            5));
                }
            }
            pool.analyzer().analyzeAll();

            clock.set(day.atTime(13, 50).toInstant(ZoneOffset.UTC));
            pool.preWarmer().tick();

            clock.set(day.atTime(14, 20).toInstant(ZoneOffset.UTC));
            int backupSelected = 0;
            int hotHourTriggers = 0;
            for (int i = 0; i < 20; i++) {
                try (PapConnection c = pool.getConnection()) {
                    if (backup.equals(c.endpointId())) {
                        backupSelected++;
                    }
                    if (c.routingDecision().isPresent()
                            && c.routingDecision().get().trigger() == RoutingDecision.Trigger.HOT_HOUR) {
                        hotHourTriggers++;
                    }
                } catch (Exception ignored) {
                    // rare
                }
            }

            assertTrue(backupSelected >= 10,
                    "expected frequent preemptive use of backup during bad window, got " + backupSelected);
            assertTrue(hotHourTriggers >= 1, "expected at least one HOT_HOUR trigger");
            assertTrue(pool.metrics().successRate() > 0.7);
        }
    }
}
