package com.college.pap.analysis;

import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.model.FailureType;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternAnalyzerTest {

    private final EndpointId endpoint = new EndpointId("primary-db");

    @Test
    void detectsHighFailureRateInTargetHour() {
        FailureHistoryStore store = new FailureHistoryStore(200);
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 10; i++) {
            Instant ts = day.atTime(14, i * 2).toInstant(ZoneOffset.UTC);
            record(store, analyzer, ConnectionAttempt.failure(
                    endpoint, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 100));
        }
        for (int i = 0; i < 10; i++) {
            Instant ts = day.atTime(9, i * 2).toInstant(ZoneOffset.UTC);
            record(store, analyzer, ConnectionAttempt.success(endpoint, ts, 10));
        }

        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertTrue(profile.failureRateAtHour(14) > 0.8, "14:00 should look bad");
        assertEquals(0.0, profile.failureRateAtHour(9), 0.001, "09:00 should be clean");
        assertTrue(profile.samplesAtHour(14) >= 10);
        assertTrue(profile.isHotHour(14, 5, 0.50));
    }

    @Test
    void detectsActiveFailureCluster() {
        FailureHistoryStore store = new FailureHistoryStore(50);
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        Instant base = Instant.parse("2026-07-26T16:00:00Z");

        record(store, analyzer, ConnectionAttempt.success(endpoint, base, 5));
        record(store, analyzer, ConnectionAttempt.failure(
                endpoint, base.plusSeconds(1), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));
        record(store, analyzer, ConnectionAttempt.failure(
                endpoint, base.plusSeconds(2), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));
        record(store, analyzer, ConnectionAttempt.failure(
                endpoint, base.plusSeconds(3), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));

        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertTrue(profile.clusterState().inFailureCluster());
        assertEquals(3, profile.clusterState().currentRunLength());
        assertTrue(profile.clusterState().clusterPenalty() > 0.3);
    }

    @Test
    void successBreaksCluster() {
        FailureHistoryStore store = new FailureHistoryStore(50);
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        Instant base = Instant.parse("2026-07-26T16:00:00Z");

        record(store, analyzer, ConnectionAttempt.failure(
                endpoint, base, AttemptOutcome.FAILURE, FailureType.SSL_RESET, 10));
        record(store, analyzer, ConnectionAttempt.failure(
                endpoint, base.plusSeconds(1), AttemptOutcome.FAILURE, FailureType.SSL_RESET, 10));
        record(store, analyzer, ConnectionAttempt.success(endpoint, base.plusSeconds(2), 8));

        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertFalse(profile.clusterState().inFailureCluster());
        assertEquals(0, profile.clusterState().currentRunLength());
        assertEquals(2.0, profile.clusterState().averageFailureRunLength(), 0.001);
    }

    /**
     * Hourly learning must outlive the ring buffer: capacity 2000 cannot hold 5 days
     * at 1 attempt / 10 s, but observe() counters still mark hour 14 as hot.
     */
    @Test
    void hourlyLearningSurvivesRingBufferEviction() {
        FailureHistoryStore store = new FailureHistoryStore(2000);
        MutableClock clock = MutableClock.utc(LocalDateTime.of(2026, 7, 20, 0, 0).toInstant(ZoneOffset.UTC));
        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC, clock);

        Instant end = LocalDateTime.of(2026, 7, 25, 0, 0).toInstant(ZoneOffset.UTC);
        while (clock.instant().isBefore(end)) {
            Instant ts = clock.instant();
            int hour = ts.atZone(ZoneOffset.UTC).getHour();
            ConnectionAttempt attempt = hour == 14
                    ? ConnectionAttempt.failure(
                            endpoint, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 50)
                    : ConnectionAttempt.success(endpoint, ts, 5);
            record(store, analyzer, attempt);
            clock.advance(Duration.ofSeconds(10));
        }

        EndpointRiskProfile profile = analyzer.analyze(endpoint);
        assertEquals(clock.instant(), profile.computedAt(), "computedAt must use injected clock");
        assertTrue(profile.samplesAtHour(14) >= 1000,
                "hour-14 samples should survive eviction, got " + profile.samplesAtHour(14));
        assertTrue(profile.isHotHour(14, 5, 0.5));
        // Ring buffer alone cannot retain 1000 hour-14 samples at capacity 2000.
        long hour14InBuffer = store.getHistory(endpoint).stream()
                .filter(a -> a.timestamp().atZone(ZoneOffset.UTC).getHour() == 14)
                .count();
        assertTrue(hour14InBuffer < 1000,
                "sanity: buffer should have dropped older hour-14 samples");
    }

    private static void record(
            FailureHistoryStore store, PatternAnalyzer analyzer, ConnectionAttempt attempt) {
        store.record(attempt);
        analyzer.observe(attempt);
    }
}
