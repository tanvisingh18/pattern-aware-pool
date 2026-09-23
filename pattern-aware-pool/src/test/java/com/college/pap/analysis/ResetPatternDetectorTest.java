package com.college.pap.analysis;

import com.college.pap.model.EndpointId;
import com.college.pap.pool.ConnectionPool;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.FlakyEndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolConfig;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecision;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResetPatternDetectorTest {

    @Test
    void detectsPeriodFromThreeConsistentStreaks() {
        ResetPatternDetector detector = new ResetPatternDetector();
        EndpointId primary = new EndpointId("primary-db");

        // Three completed streaks near 47 (±2): 46, 47, 48
        feedStreak(detector, primary, 46);
        feedStreak(detector, primary, 47);
        feedStreak(detector, primary, 48);

        assertEquals(47, detector.detectedPeriod(primary).orElse(-1));

        // Advance live streak to P-1
        for (int i = 0; i < 46; i++) {
            detector.observe(primary, true);
        }
        assertTrue(detector.preferBackup(primary));
    }

    @Test
    void seededFlakyResetEventuallyPredictsViaRouting() throws Exception {
        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(10, 0).toInstant(ZoneOffset.UTC));

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        Random rng = new Random(7);
        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.resetOnly(47), clock, rng));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock, new Random(8)));

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setReuseEnabled(false);
        config.setHighRiskThreshold(0.99); // force reset trigger, not score

        try (ConnectionPool pool = ConnectionPool.predictiveManual(registry, connectors, config, clock)) {
            // Drive enough primary connects to finish ≥3 reset cycles (47 successes + 1 fail each).
            // Force primary by deciding outside, then recording via getConnection when primary OK.
            // With high threshold and no hot hour, routing stays on primary until reset predicts.
            int cycles = 0;
            int attempts = 0;
            boolean predicted = false;
            while (attempts < 250 && !predicted) {
                clock.set(day.atTime(10, 0).plusSeconds(attempts).toInstant(ZoneOffset.UTC));
                try (PapConnection c = pool.getConnection(primary)) {
                    RoutingDecision d = c.routingDecision().orElseThrow();
                    if (d.trigger() == RoutingDecision.Trigger.RESET_PREDICTED) {
                        predicted = true;
                        assertEquals(backup, d.selected());
                    }
                } catch (EndpointConnector.ConnectionFailedException ignored) {
                    // reset failure on primary; pool may failover
                }
                if (pool.resetDetector().detectedPeriod(primary).isPresent()) {
                    cycles = pool.resetDetector().completedStreaks(primary).size();
                }
                attempts++;
            }

            assertTrue(cycles >= 3,
                    "expected ≥3 completed reset streaks, got " + cycles
                            + " streaks=" + pool.resetDetector().completedStreaks(primary));
            assertTrue(predicted,
                    "expected RESET_PREDICTED after learning period P≈47; streaks="
                            + pool.resetDetector().completedStreaks(primary)
                            + " period=" + pool.resetDetector().detectedPeriod(primary)
                            + " streak=" + pool.resetDetector().currentSuccessStreak(primary));
        }
    }

    private static void feedStreak(ResetPatternDetector detector, EndpointId id, int successes) {
        for (int i = 0; i < successes; i++) {
            detector.observe(id, true);
        }
        detector.observe(id, false);
    }
}
