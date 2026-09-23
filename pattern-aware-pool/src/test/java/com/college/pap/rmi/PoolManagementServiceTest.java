package com.college.pap.rmi;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskWeights;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecider;
import com.college.pap.routing.RoutingDecision;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies PoolManagementService-style config mutation (without RMI export).
 * Changing highRiskThreshold on the shared PoolConfig is visible to RoutingDecider.
 */
class PoolManagementServiceTest {

    @Test
    void configMutationViaServiceStyleSetterAffectsRouting() {
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        FailureHistoryStore store = new FailureHistoryStore(200);
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 8; i++) {
            Instant ts = day.atTime(9, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.success(primary, ts, 5));
            store.record(ConnectionAttempt.success(backup, ts, 5));
        }
        store.record(ConnectionAttempt.failure(
                primary,
                day.atTime(9, 20).toInstant(ZoneOffset.UTC),
                AttemptOutcome.FAILURE,
                FailureType.TIMEOUT,
                15));

        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        analyzer.analyzeAll();

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHighRiskThreshold(0.55);

        Instant at = day.atTime(9, 25).toInstant(ZoneOffset.UTC);
        PredictionEngine engine = new PredictionEngine(
                RiskWeights.defaults(), Clock.fixed(at, ZoneOffset.UTC));
        RoutingDecider routingDecider = new RoutingDecider(
                EndpointRegistry.of(primary, backup), analyzer, engine, config);

        // Simulate PoolManagementService.setHighRiskThreshold without RMI export.
        config.setHighRiskThreshold(0.0);
        assertEquals(0.0, routingDecider.highRiskThreshold(), 0.0001);

        RoutingDecision decision = routingDecider.decide(primary, at);
        assertTrue(decision.rerouted());
        assertTrue(decision.reason() == RoutingDecision.Reason.PREEMPTIVE_FAILOVER
                || decision.reason() == RoutingDecision.Reason.DEGRADED_MODE);
        assertEquals(backup, decision.selected());
    }
}
