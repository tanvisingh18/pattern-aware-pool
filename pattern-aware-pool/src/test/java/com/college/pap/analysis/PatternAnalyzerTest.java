package com.college.pap.analysis;

import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.model.FailureType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternAnalyzerTest {

    private final EndpointId endpoint = new EndpointId("primary-db");

    @Test
    void detectsHighFailureRateInTargetHour() {
        FailureHistoryStore store = new FailureHistoryStore(200);
        LocalDate day = LocalDate.of(2026, 7, 26);

        for (int i = 0; i < 10; i++) {
            Instant ts = day.atTime(14, i * 2).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.failure(
                    endpoint, ts, AttemptOutcome.TIMEOUT, FailureType.TIMEOUT, 100));
        }
        for (int i = 0; i < 10; i++) {
            Instant ts = day.atTime(9, i * 2).toInstant(ZoneOffset.UTC);
            store.record(ConnectionAttempt.success(endpoint, ts, 10));
        }

        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertTrue(profile.failureRateAtHour(14) > 0.8, "14:00 should look bad");
        assertEquals(0.0, profile.failureRateAtHour(9), 0.001, "09:00 should be clean");
        assertTrue(profile.samplesAtHour(14) >= 10);
        assertTrue(profile.isHotHour(14, 5, 0.50));
    }

    @Test
    void detectsActiveFailureCluster() {
        FailureHistoryStore store = new FailureHistoryStore(50);
        Instant base = Instant.parse("2026-07-26T16:00:00Z");

        store.record(ConnectionAttempt.success(endpoint, base, 5));
        store.record(ConnectionAttempt.failure(
                endpoint, base.plusSeconds(1), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));
        store.record(ConnectionAttempt.failure(
                endpoint, base.plusSeconds(2), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));
        store.record(ConnectionAttempt.failure(
                endpoint, base.plusSeconds(3), AttemptOutcome.FAILURE, FailureType.NETWORK_UNREACHABLE, 20));

        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, java.time.ZoneOffset.UTC);
        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertTrue(profile.clusterState().inFailureCluster());
        assertEquals(3, profile.clusterState().currentRunLength());
        assertTrue(profile.clusterState().clusterPenalty() > 0.3);
    }

    @Test
    void successBreaksCluster() {
        FailureHistoryStore store = new FailureHistoryStore(50);
        Instant base = Instant.parse("2026-07-26T16:00:00Z");

        store.record(ConnectionAttempt.failure(
                endpoint, base, AttemptOutcome.FAILURE, FailureType.SSL_RESET, 10));
        store.record(ConnectionAttempt.failure(
                endpoint, base.plusSeconds(1), AttemptOutcome.FAILURE, FailureType.SSL_RESET, 10));
        store.record(ConnectionAttempt.success(endpoint, base.plusSeconds(2), 8));

        PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, java.time.ZoneOffset.UTC);
        EndpointRiskProfile profile = analyzer.analyze(endpoint);

        assertFalse(profile.clusterState().inFailureCluster());
        assertEquals(0, profile.clusterState().currentRunLength());
        assertEquals(2.0, profile.clusterState().averageFailureRunLength(), 0.001);
    }
}
