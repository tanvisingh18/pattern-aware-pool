package com.college.pap.routing;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.model.FailureType;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskWeights;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hot-hour gating uses EWMA (not lifetime plain rate). After the hour is hot,
 * successful recovery observations must cool EWMA so routing returns to primary.
 */
class HotHourEwmaCooldownTest {

    @Test
    void twoSuccessfulProbesCoolEwmaAndClearHotHourFailover() {
        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        FailureHistoryStore store = new FailureHistoryStore(5000);
        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(14, 0).toInstant(ZoneOffset.UTC));
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC, clock);

        // Build hour-14 EWMA ≥ 0.85 via many failures (after warm-up, EWMA tracks failures).
        for (int i = 0; i < 40; i++) {
            Instant ts = day.atTime(14, 0).plusSeconds(i).toInstant(ZoneOffset.UTC);
            ConnectionAttempt fail = ConnectionAttempt.failure(
                    primary, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 50);
            store.record(fail);
            analyzer.observe(fail);
        }
        for (int i = 0; i < 40; i++) {
            Instant ts = day.atTime(14, 0).plusSeconds(i).toInstant(ZoneOffset.UTC);
            ConnectionAttempt ok = ConnectionAttempt.success(backup, ts, 5);
            store.record(ok);
            analyzer.observe(ok);
        }
        analyzer.analyzeAll();

        EndpointRiskProfile hot = analyzer.getProfile(primary);
        assertTrue(hot.failureRateAtHour(14) >= 0.85,
                "expected EWMA ≥ 0.85, got " + hot.failureRateAtHour(14));
        assertTrue(hot.isHotHour(14, 5, 0.50));

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHotHourMinSamples(5);
        config.setHotHourThreshold(0.50);
        config.setHighRiskThreshold(0.99);

        Instant at = day.atTime(14, 5).toInstant(ZoneOffset.UTC);
        PredictionEngine engine = new PredictionEngine(
                RiskWeights.defaults(), Clock.fixed(at, ZoneOffset.UTC));
        RoutingDecider decider = new RoutingDecider(
                EndpointRegistry.of(primary, backup), analyzer, engine, config);

        RoutingDecision whileHot = decider.decide(primary, at);
        assertEquals(RoutingDecision.Trigger.HOT_HOUR, whileHot.trigger());
        assertEquals(backup, whileHot.selected());

        double ewmaBefore = analyzer.getProfile(primary).failureRateAtHour(14);

        // Two successful recovery probes 30 s apart (observe-only, as BackupPreWarmer does).
        clock.set(day.atTime(14, 10).toInstant(ZoneOffset.UTC));
        ConnectionAttempt probe1 = ConnectionAttempt.success(primary, clock.instant(), 5);
        store.record(probe1);
        analyzer.observe(probe1);

        clock.advance(Duration.ofSeconds(30));
        ConnectionAttempt probe2 = ConnectionAttempt.success(primary, clock.instant(), 5);
        store.record(probe2);
        analyzer.observe(probe2);

        analyzer.analyzeAll();
        double ewmaAfter = analyzer.getProfile(primary).failureRateAtHour(14);
        double expected = ewmaBefore * 0.65 * 0.65;
        assertEquals(expected, ewmaAfter, 0.02,
                "EWMA should cool ≈ α=0.35 → ×0.65² after two successes");
        assertTrue(ewmaAfter < 0.50, "cooled EWMA should fall below hot-hour threshold");
        assertFalse(analyzer.getProfile(primary).isHotHour(14, 5, 0.50));

        // Within 60 s of first probe, routing returns to primary with trigger NONE.
        Instant decideAt = clock.instant();
        assertTrue(Duration.between(day.atTime(14, 10).toInstant(ZoneOffset.UTC), decideAt)
                .compareTo(Duration.ofSeconds(60)) <= 0);
        PredictionEngine engine2 = new PredictionEngine(
                RiskWeights.defaults(), Clock.fixed(decideAt, ZoneOffset.UTC));
        RoutingDecider decider2 = new RoutingDecider(
                EndpointRegistry.of(primary, backup), analyzer, engine2, config);
        RoutingDecision cooled = decider2.decide(primary, decideAt);
        assertEquals(primary, cooled.selected());
        assertEquals(RoutingDecision.Trigger.NONE, cooled.trigger());
    }
}
