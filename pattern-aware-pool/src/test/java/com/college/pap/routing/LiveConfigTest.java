package com.college.pap.routing;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskWeights;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live PoolConfig mutations must be visible to RoutingDecider immediately.
 */
class LiveConfigTest {

    @Test
    void loweringThresholdToZeroCausesImmediateFailover() {
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        FailureHistoryStore store = new FailureHistoryStore(200);
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 10; i++) {
            Instant ts = day.atTime(10, i).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.success(primary, ts, 5));
            store.record(ConnectionAttempt.success(backup, ts, 5));
        }
        store.record(ConnectionAttempt.failure(
                primary,
                day.atTime(10, 30).toInstant(ZoneOffset.UTC),
                AttemptOutcome.FAILURE,
                FailureType.TIMEOUT,
                20));

        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        analyzer.analyzeAll();

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHighRiskThreshold(0.55);

        Instant at = day.atTime(10, 35).toInstant(ZoneOffset.UTC);
        PredictionEngine engine = new PredictionEngine(
                RiskWeights.defaults(), Clock.fixed(at, ZoneOffset.UTC));
        RoutingDecider decider = new RoutingDecider(
                EndpointRegistry.of(primary, backup), analyzer, engine, config);

        RoutingDecision before = decider.decide(primary, at);
        assertEquals(RoutingDecision.Reason.PRIMARY_OK, before.reason());

        config.setHighRiskThreshold(0.0);
        assertEquals(0.0, decider.highRiskThreshold(), 0.0001);

        RoutingDecision after = decider.decide(primary, at);
        assertEquals(0.0, decider.highRiskThreshold(), 0.0001);
        assertTrue(after.rerouted(), "threshold 0.0 must failover away from primary");
        assertEquals(backup, after.selected());
        assertTrue(after.reason() == RoutingDecision.Reason.PREEMPTIVE_FAILOVER
                || after.reason() == RoutingDecision.Reason.DEGRADED_MODE);
    }
}
