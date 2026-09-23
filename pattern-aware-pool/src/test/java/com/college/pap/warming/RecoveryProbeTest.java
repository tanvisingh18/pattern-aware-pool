package com.college.pap.warming;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.monitoring.MonitoringThreadFactory;
import com.college.pap.monitoring.PoolMetrics;
import com.college.pap.pool.ConnectionValidator;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.FlakyEndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecider;
import com.college.pap.routing.RoutingDecision;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * After primary is avoided due to a live failure cluster, a successful recovery
 * probe must break the cluster and allow traffic to return.
 */
class RecoveryProbeTest {

    @Test
    void successfulProbeClearsLiveClusterAvoidance() throws Exception {
        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(11, 0).toInstant(ZoneOffset.UTC));

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        FailureHistoryStore store = new FailureHistoryStore(200);
PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        Instant base = day.atTime(11, 0).toInstant(ZoneOffset.UTC);
        // Seed a live failure cluster on primary (run length >= 3).
        for (int i = 0; i < 3; i++) {
            { ConnectionAttempt obs1 = ConnectionAttempt.failure(
                    primary,
                    base.plusSeconds(i),
                    AttemptOutcome.FAILURE,
                    FailureType.NETWORK_UNREACHABLE,
                    20);
              store.record(obs1);
              analyzer.observe(obs1); }
        }
        for (int i = 0; i < 5; i++) {
            { ConnectionAttempt obs2 = ConnectionAttempt.success(backup, base.plusSeconds(i), 5);
              store.record(obs2);
              analyzer.observe(obs2); }
        }

                analyzer.analyzeAll();

        PoolConfig config = new PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setClusterAvoidRun(3);
        config.setHighRiskThreshold(0.99); // rely on LIVE_CLUSTER, not score
        config.setHotHourMinSamples(50);   // disable hot-hour for this scenario
        config.setRecoveryProbeSeconds(1);
        config.setWarmPoolSize(1);

        AtomicBoolean primaryUp = new AtomicBoolean(false);
        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, new EndpointConnector() {
            @Override
            public EndpointId endpointId() {
                return primary;
            }

            @Override
            public PapConnection connect() throws ConnectionFailedException {
                if (!primaryUp.get()) {
                    throw new ConnectionFailedException("still down", FailureType.NETWORK_UNREACHABLE);
                }
                return new PapConnection(primary, "ok", () -> {}, false);
            }
        });
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock));

        PredictionEngine engine = new PredictionEngine(config, clock);
        RoutingDecider decider = new RoutingDecider(registry, analyzer, engine, config);

        RoutingDecision before = decider.decide(primary, clock.instant());
        assertEquals(RoutingDecision.Reason.PREEMPTIVE_FAILOVER, before.reason());
        assertEquals(RoutingDecision.Trigger.LIVE_CLUSTER, before.trigger());

        MonitoringThreadFactory tf = new MonitoringThreadFactory("probe-mon", "p");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        WarmPool warmPool = new WarmPool(backup, 1);
        PoolMetrics metrics = new PoolMetrics();

        BackupPreWarmer preWarmer = new BackupPreWarmer(
                registry,
                connectors,
                analyzer,
                engine,
                new ConnectionValidator(),
                warmPool,
                metrics,
                store,
                scheduler,
                config,
                clock);

        // Bring primary back, then probe while it is still marked avoided.
        primaryUp.set(true);
        preWarmer.tick();
        assertTrue(preWarmer.isPrimaryAvoided() || metrics.recoveryProbesOk() >= 1);
        if (metrics.recoveryProbesOk() < 1) {
            clock.set(clock.instant().plusSeconds(2));
            preWarmer.probePrimaryNow();
        }

        assertTrue(metrics.recoveryProbesOk() >= 1, "expected a successful recovery probe");

        RoutingDecision after = decider.decide(primary, clock.instant());
        assertEquals(primary, after.selected());
        assertEquals(RoutingDecision.Reason.PRIMARY_OK, after.reason());
        assertEquals(0, analyzer.getProfile(primary).clusterState().currentRunLength());

        preWarmer.close();
        scheduler.shutdownNow();
    }
}
