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
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.util.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupPreWarmerTest {

    @Test
    void preWarmsBackupBeforeBadHour() {
        LocalDate day = LocalDate.of(2026, 7, 26);
        MutableClock clock = MutableClock.utc(day.atTime(13, 50).toInstant(ZoneOffset.UTC));

        EndpointId primary = new EndpointId("primary-db");
        EndpointId backup = new EndpointId("backup-db");
        EndpointRegistry registry = EndpointRegistry.of(primary, backup);

        FailureHistoryStore store = new FailureHistoryStore(300);
PatternAnalyzer analyzer = new PatternAnalyzer(store, 0.35, 20, 2, ZoneOffset.UTC);
        for (int i = 0; i < 20; i++) {
            { ConnectionAttempt obs1 = ConnectionAttempt.failure(
                    primary,
                    day.atTime(14, i).toInstant(ZoneOffset.UTC),
                    AttemptOutcome.TIMEOUT,
                    FailureType.TIMEOUT,
                    50);
              store.record(obs1);
              analyzer.observe(obs1); }
        }
        for (int i = 0; i < 20; i++) {
            { ConnectionAttempt obs2 = ConnectionAttempt.success(
                    backup,
                    day.atTime(13, i).toInstant(ZoneOffset.UTC),
                    10);
              store.record(obs2);
              analyzer.observe(obs2); }
        }

                analyzer.analyzeAll();

        Map<EndpointId, EndpointConnector> connectors = new LinkedHashMap<>();
        connectors.put(primary, FlakyEndpointConnector.forTests(
                primary, FlakyEndpointConnector.PatternConfig.primaryFlaky(), clock));
        connectors.put(backup, FlakyEndpointConnector.forTests(
                backup, FlakyEndpointConnector.PatternConfig.healthyBackup(), clock));

        WarmPool warmPool = new WarmPool(backup, 5);
        MonitoringThreadFactory tf = new MonitoringThreadFactory("test-mon", "t");
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        PoolMetrics metrics = new PoolMetrics();
        com.college.pap.pool.PoolConfig config = new com.college.pap.pool.PoolConfig();
        config.setZoneId(ZoneOffset.UTC);
        config.setHighRiskThreshold(0.55);
        config.setPreWarmLeadMinutes(15);
        config.setWarmPoolSize(5);

        BackupPreWarmer preWarmer = new BackupPreWarmer(
                registry,
                connectors,
                analyzer,
                new PredictionEngine(config, clock),
                new ConnectionValidator(),
                warmPool,
                metrics,
                store,
                scheduler,
                config,
                clock);

        preWarmer.tick();
        assertTrue(warmPool.size() > 0, "backup should be pre-warmed before 14:00");
        assertTrue(metrics.preWarmEvents() > 0);
        preWarmer.close();
        scheduler.shutdownNow();
    }
}
