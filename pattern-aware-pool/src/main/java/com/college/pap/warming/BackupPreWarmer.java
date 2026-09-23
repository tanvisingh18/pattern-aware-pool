package com.college.pap.warming;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.model.FailureType;
import com.college.pap.monitoring.PoolMetrics;
import com.college.pap.pool.ConnectionValidator;
import com.college.pap.pool.EndpointConnector;
import com.college.pap.pool.PapConnection;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskScore;
import com.college.pap.routing.EndpointRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Layer 3 — Pre-warming + primary recovery probes.
 *
 * <ul>
 *   <li>Pre-warms backup when primary is hot-hour / high-risk within lead time</li>
 *   <li>While primary is avoided, probes it every N seconds without charging user requests</li>
 *   <li>Reads threshold LIVE from PoolConfig (RMI retune works)</li>
 * </ul>
 */
public final class BackupPreWarmer implements AutoCloseable {

    private final EndpointRegistry registry;
    private final Map<EndpointId, EndpointConnector> connectors;
    private final PatternAnalyzer analyzer;
    private final PredictionEngine predictionEngine;
    private final ConnectionValidator validator;
    private final WarmPool warmPool;
    private final PoolMetrics metrics;
    private final FailureHistoryStore historyStore;
    private final ScheduledExecutorService scheduler;
    private final DoubleSupplier thresholdSupplier;
    private final PoolConfig config;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ConcurrentHashMap<EndpointId, Boolean> lastWarmState = new ConcurrentHashMap<>();
    private final AtomicLong lastProbeEpochMs = new AtomicLong(0);

    public BackupPreWarmer(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            ConnectionValidator validator,
            WarmPool warmPool,
            PoolMetrics metrics,
            FailureHistoryStore historyStore,
            ScheduledExecutorService scheduler,
            PoolConfig config,
            Clock clock) {
        this.registry = Objects.requireNonNull(registry);
        this.connectors = Objects.requireNonNull(connectors);
        this.analyzer = Objects.requireNonNull(analyzer);
        this.predictionEngine = Objects.requireNonNull(predictionEngine);
        this.validator = Objects.requireNonNull(validator);
        this.warmPool = Objects.requireNonNull(warmPool);
        this.metrics = Objects.requireNonNull(metrics);
        this.historyStore = Objects.requireNonNull(historyStore);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.config = Objects.requireNonNull(config);
        this.thresholdSupplier = config::highRiskThreshold;
        this.clock = Objects.requireNonNull(clock);
    }

    public void start(long periodSeconds) {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(this::tickSafe, 0, periodSeconds, TimeUnit.SECONDS);
        }
    }

    public void tick() {
        Instant now = clock.instant();
        analyzer.analyzeAll();

        EndpointId primary = registry.primary();
        boolean shouldWarm = isHighRiskSoon(primary, now);
        lastWarmState.put(primary, shouldWarm);

        if (shouldWarm) {
            preWarmBackup();
            maybeProbePrimary(now);
        }
    }

    private void preWarmBackup() {
        EndpointId backup = registry.firstBackup().orElse(null);
        if (backup == null || !backup.equals(warmPool.endpointId())) {
            return;
        }
        EndpointConnector connector = connectors.get(backup);
        if (connector == null) {
            return;
        }
        int target = config.warmPoolSize();
        while (warmPool.size() < target) {
            try {
                PapConnection raw = connector.connect();
                PapConnection warmed = new PapConnection(
                        raw.endpointId(),
                        raw.nativeHandle(),
                        () -> raw.destroy(),
                        true);
                if (!validator.validate(warmed) || !warmPool.offer(warmed)) {
                    warmed.destroy();
                    break;
                }
                metrics.recordPreWarm();
            } catch (Exception e) {
                break;
            }
        }
    }

    /**
     * Recovery probe: while primary is avoided, attempt connect+validate in background
     * and record the result into history so risk can fall and traffic can return.
     */
    private void maybeProbePrimary(Instant now) {
        long intervalMs = config.recoveryProbeSeconds() * 1000L;
        long last = lastProbeEpochMs.get();
        if (now.toEpochMilli() - last < intervalMs) {
            return;
        }
        if (!lastProbeEpochMs.compareAndSet(last, now.toEpochMilli())) {
            return;
        }

        EndpointId primary = registry.primary();
        EndpointConnector connector = connectors.get(primary);
        if (connector == null) {
            return;
        }
        Instant started = clock.instant();
        try {
            PapConnection probe = connector.connect();
            boolean ok = validator.validate(probe);
            probe.destroy();
            if (ok) {
                ConnectionAttempt success = ConnectionAttempt.success(primary, started, 5);
                historyStore.record(success);
                analyzer.observe(success);
                analyzer.analyze(primary);
                metrics.recordRecoveryProbe(true);
            } else {
                ConnectionAttempt failure = ConnectionAttempt.failure(
                        primary, started, AttemptOutcome.FAILURE, FailureType.UNKNOWN, 5);
                historyStore.record(failure);
                analyzer.observe(failure);
                analyzer.analyze(primary);
                metrics.recordRecoveryProbe(false);
            }
        } catch (EndpointConnector.ConnectionFailedException e) {
            ConnectionAttempt failure = ConnectionAttempt.failure(
                    primary, started, AttemptOutcome.FAILURE, e.failureType(), 20);
            historyStore.record(failure);
            analyzer.observe(failure);
            analyzer.analyze(primary);
            metrics.recordRecoveryProbe(false);
        } catch (Exception e) {
            metrics.recordRecoveryProbe(false);
        }
    }

    public boolean isWarmingFor(EndpointId endpointId) {
        return Boolean.TRUE.equals(lastWarmState.get(endpointId));
    }

    public WarmPool warmPool() {
        return warmPool;
    }

    private boolean isHighRiskSoon(EndpointId endpointId, Instant now) {
        EndpointRiskProfile profile = analyzer.getProfile(endpointId);
        if (profile == null) {
            profile = analyzer.analyze(endpointId);
        }
        if (profile == null || profile.sampleCount() == 0) {
            return false;
        }

        double threshold = thresholdSupplier.getAsDouble();
        int minSamples = config.hotHourMinSamples();
        double hotRate = config.hotHourMinRate();

        RiskScore nowScore = predictionEngine.score(profile, now);
        if (nowScore.isHighRisk(threshold)
                || profile.isHotHour(nowScore.hourOfDay(), minSamples, hotRate)) {
            return true;
        }

        int lead = config.preWarmLeadMinutes();
        for (int m = 1; m <= Math.max(1, lead); m++) {
            Instant future = now.plus(m, ChronoUnit.MINUTES);
            RiskScore futureScore = predictionEngine.score(profile, future);
            if (futureScore.isHighRisk(threshold)
                    || profile.isHotHour(futureScore.hourOfDay(), minSamples, hotRate)) {
                return true;
            }
        }
        return false;
    }

    private void tickSafe() {
        try {
            tick();
        } catch (RuntimeException ignored) {
            // keep scheduler alive
        }
    }

    @Override
    public void close() {
        running.set(false);
        warmPool.drainAndClose();
    }
}
