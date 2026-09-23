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
 * Hot-hour alone must drive PREEMPTIVE_FAILOVER without needing a live primary connect.
 */
class HotHourFailoverTest {

    @Test
    void fiveDaysOfHour14FailuresTriggerHotHourFailoverOnDaySix() {
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        FailureHistoryStore store = new FailureHistoryStore(5000);
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        LocalDate start = LocalDate.of(2026, 7, 20);

        // 5 days × several samples at hour 14, all failures on primary.
        for (int day = 0; day < 5; day++) {
            LocalDate d = start.plusDays(day);
            for (int i = 0; i < 6; i++) {
                Instant ts = d.atTime(14, i * 5).toInstant(ZoneOffset.UTC);
                ConnectionAttempt fail = ConnectionAttempt.failure(
                        primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 80);
                store.record(fail);
                analyzer.observe(fail);
            }
            for (int i = 0; i < 6; i++) {
                Instant ts = d.atTime(14, i * 5).toInstant(ZoneOffset.UTC);
                ConnectionAttempt ok = ConnectionAttempt.success(backup, ts, 5);
                store.record(ok);
                analyzer.observe(ok);
            }
        }

        analyzer.analyzeAll();

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHotHourMinSamples(5);
        config.setHotHourThreshold(0.50);
        // Keep score threshold high so HOT_HOUR is the distinctive trigger.
        config.setHighRiskThreshold(0.99);

        Instant day6At14 = start.plusDays(5).atTime(14, 0).toInstant(ZoneOffset.UTC);
        PredictionEngine engine = new PredictionEngine(
                RiskWeights.defaults(),
                Clock.fixed(day6At14, ZoneOffset.UTC));
        RoutingDecider decider = new RoutingDecider(
                EndpointRegistry.of(primary, backup), analyzer, engine, config);

        RoutingDecision decision = decider.decide(primary, day6At14);

        assertEquals(RoutingDecision.Reason.PREEMPTIVE_FAILOVER, decision.reason());
        assertEquals(RoutingDecision.Trigger.HOT_HOUR, decision.trigger());
        assertEquals(backup, decision.selected());
        assertTrue(decision.rerouted());
        assertTrue(analyzer.getProfile(primary).isHotHour(14, 5, 0.50));
    }
}
