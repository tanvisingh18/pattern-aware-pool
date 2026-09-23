package com.college.pap.routing;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskWeights;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingDeciderTest {

    private final EndpointId primary = new EndpointId("primary-db");
    private final EndpointId backup = new EndpointId("backup-db");

    private FailureHistoryStore store;
    private PatternAnalyzer analyzer;
    private RoutingDecider decider;

    @BeforeEach
    void setUp() {
        store = new FailureHistoryStore(300);
        analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        PredictionEngine engine = new PredictionEngine(
                RiskWeights.defaults(),
                java.time.Clock.fixed(Instant.parse("2026-07-26T14:00:00Z"), ZoneOffset.UTC));
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);
        com.college.pap.pool.PoolConfig config = new com.college.pap.pool.PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHighRiskThreshold(0.55);
        decider = new RoutingDecider(registry, analyzer, engine, config);
    }

    @Test
    void usesPrimaryWhenHealthy() {
        LocalDate day = LocalDate.of(2026, 7, 26);
        for (int i = 0; i < 20; i++) {
            Instant ts = day.atTime(9, i).toInstant(ZoneOffset.UTC);
            { ConnectionAttempt obs1 = ConnectionAttempt.success(primary, ts, 5);
              store.record(obs1);
              analyzer.observe(obs1); }
            { ConnectionAttempt obs2 = ConnectionAttempt.success(backup, ts, 5);
              store.record(obs2);
              analyzer.observe(obs2); }
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(9, 30).toInstant(ZoneOffset.UTC));

        assertEquals(primary, decision.selected());
        assertEquals(RoutingDecision.Reason.PRIMARY_OK, decision.reason());
        assertFalse(decision.rerouted());
    }

    @Test
    void preemptivelyFailsOverDuringBadWindow() {
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            { ConnectionAttempt obs3 = ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200);
              store.record(obs3);
              analyzer.observe(obs3); }
        }
        for (int i = 0; i < 3; i++) {
            Instant ts = day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC);
            { ConnectionAttempt obs4 = ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 40);
              store.record(obs4);
              analyzer.observe(obs4); }
        }
        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            { ConnectionAttempt obs5 = ConnectionAttempt.success(backup, ts, 6);
              store.record(obs5);
              analyzer.observe(obs5); }
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(14, 20).toInstant(ZoneOffset.UTC));

        assertEquals(backup, decision.selected());
        assertEquals(RoutingDecision.Reason.PREEMPTIVE_FAILOVER, decision.reason());
        assertTrue(decision.rerouted());
    }

    @Test
    void degradedModePicksLeastBadWhenAllRisky() {
        LocalDate day = LocalDate.of(2026, 7, 26);

        // Both endpoints fail hard in the 14:00 window and end in active clusters.
        for (int i = 0; i < 15; i++) {
            Instant ts = day.atTime(14, i).toInstant(ZoneOffset.UTC);
            { ConnectionAttempt obs6 = ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200);
              store.record(obs6);
              analyzer.observe(obs6); }
            { ConnectionAttempt obs7 = ConnectionAttempt.failure(
                    backup, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 200);
              store.record(obs7);
              analyzer.observe(obs7); }
        }
        // Primary has a longer trailing cluster → higher risk than backup.
        for (int i = 0; i < 4; i++) {
            { ConnectionAttempt obs8 = ConnectionAttempt.failure(
                    primary,
                    day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40);
              store.record(obs8);
              analyzer.observe(obs8); }
        }
        for (int i = 0; i < 2; i++) {
            { ConnectionAttempt obs9 = ConnectionAttempt.failure(
                    backup,
                    day.atTime(14, 40 + i).toInstant(ZoneOffset.UTC),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    40);
              store.record(obs9);
              analyzer.observe(obs9); }
        }
        analyzer.analyzeAll();

        RoutingDecision decision = decider.decide(primary, day.atTime(14, 55).toInstant(ZoneOffset.UTC));

        assertEquals(RoutingDecision.Reason.DEGRADED_MODE, decision.reason());
        assertTrue(decision.allScores().get(primary).isHighRisk(0.55));
        assertTrue(decision.allScores().get(backup).isHighRisk(0.55));
        // Must pick the least-bad endpoint among the high-risk set.
        double selected = decision.selectedScore().score();
        assertTrue(selected <= decision.allScores().get(primary).score() + 1e-9);
        assertTrue(selected <= decision.allScores().get(backup).score() + 1e-9);
    }
}
