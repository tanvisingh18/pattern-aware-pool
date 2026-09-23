package com.college.pap.pool;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.analysis.ResetPatternDetector;
import com.college.pap.history.FailureHistoryStore;
import com.college.pap.model.AttemptOutcome;
import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;
import com.college.pap.monitoring.MonitoringThreadFactory;
import com.college.pap.monitoring.PoolMetrics;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskScore;
import com.college.pap.routing.EndpointRegistry;
import com.college.pap.routing.RoutingDecider;
import com.college.pap.routing.RoutingDecision;
import com.college.pap.warming.BackupPreWarmer;
import com.college.pap.warming.WarmPool;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrator: Layer 1 memory + Layer 2 routing + Layer 3 pre-warming + real idle pooling.
 * Connections are reused via {@link IdleConnectionPool}; {@link PapConnection#close()} returns them.
 *
 * Modes: predictive, reactive baseline, and circuit-breaker baseline.
 */
public final class ConnectionPool implements AutoCloseable {

    public enum Mode {
        PREDICTIVE,
        REACTIVE,
        CIRCUIT_BREAKER
    }

    private final EndpointRegistry registry;
    private final Map<EndpointId, EndpointConnector> connectors;
    private final Map<EndpointId, IdleConnectionPool> idlePools = new ConcurrentHashMap<>();
    private final FailureHistoryStore historyStore;
    private final PatternAnalyzer analyzer;
    private final ResetPatternDetector resetDetector;
    private final PredictionEngine predictionEngine;
    private final RoutingDecider routingDecider;
    private final WarmPool warmPool;
    private final BackupPreWarmer preWarmer;
    private final PoolMetrics metrics;
    private final PoolConfig config;
    private final ScheduledExecutorService scheduler;
    private final MonitoringThreadFactory threadFactory;
    private final Clock clock;
    private final Mode mode;
    private final ConnectionValidator validator = new ConnectionValidator();

    /** Circuit breaker: open after 3 consecutive primary failures for 60s, then half-open probe. */
    private final AtomicInteger consecutivePrimaryFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>();
    private final AtomicReference<CircuitState> circuitState =
            new AtomicReference<>(CircuitState.CLOSED);

    private enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    private ConnectionPool(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PoolConfig config,
            Clock clock,
            Mode mode,
            boolean startBackground) {
        this.registry = Objects.requireNonNull(registry);
        this.connectors = Map.copyOf(connectors);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.mode = Objects.requireNonNull(mode);

        ZoneId zone = config.zoneId();
        this.historyStore = new FailureHistoryStore(config.historyCapacity());
        this.analyzer = new PatternAnalyzer(
                historyStore, 0.35, 20, 2, zone);
        this.resetDetector = new ResetPatternDetector();
        // LIVE config suppliers — RMI retune takes effect immediately.
        this.predictionEngine = new PredictionEngine(config, clock);
        this.routingDecider = new RoutingDecider(
                registry, analyzer, predictionEngine, config, resetDetector);
        this.metrics = new PoolMetrics();
        this.threadFactory = new MonitoringThreadFactory("pap-monitoring", "pap-worker");
        this.scheduler = Executors.newScheduledThreadPool(3, threadFactory);

        for (Map.Entry<EndpointId, EndpointConnector> e : this.connectors.entrySet()) {
            idlePools.put(
                    e.getKey(),
                    new IdleConnectionPool(
                            e.getValue(),
                            validator,
                            config.maxPoolSizePerEndpoint(),
                            config::reuseEnabled));
        }

        EndpointId warmTarget = registry.firstBackup().orElse(registry.primary());
        this.warmPool = new WarmPool(warmTarget, config.warmPoolSize());
        this.preWarmer = new BackupPreWarmer(
                registry,
                this.connectors,
                analyzer,
                predictionEngine,
                validator,
                warmPool,
                metrics,
                historyStore,
                scheduler,
                config,
                clock);

        boolean predictiveMode = mode == Mode.PREDICTIVE;
        if (startBackground && predictiveMode) {
            scheduler.scheduleAtFixedRate(
                    this::backgroundTickSafe,
                    0,
                    Math.max(1, config.analyzerPeriodSeconds()),
                    TimeUnit.SECONDS);
            preWarmer.start(Math.max(1, config.preWarmPeriodSeconds()));
        }
    }

    public static ConnectionPool predictive(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PoolConfig config,
            Clock clock) {
        return new ConnectionPool(registry, connectors, config, clock, Mode.PREDICTIVE, true);
    }

    /** Predictive pool without wall-clock background threads (experiments drive ticks). */
    public static ConnectionPool predictiveManual(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PoolConfig config,
            Clock clock) {
        return new ConnectionPool(registry, connectors, config, clock, Mode.PREDICTIVE, false);
    }

    public static ConnectionPool reactiveBaseline(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PoolConfig config,
            Clock clock) {
        return new ConnectionPool(registry, connectors, config, clock, Mode.REACTIVE, false);
    }

    public static ConnectionPool circuitBreaker(
            EndpointRegistry registry,
            Map<EndpointId, EndpointConnector> connectors,
            PoolConfig config,
            Clock clock) {
        return new ConnectionPool(registry, connectors, config, clock, Mode.CIRCUIT_BREAKER, false);
    }

    public PapConnection getConnection() throws EndpointConnector.ConnectionFailedException {
        return getConnection(registry.primary());
    }

    public PapConnection getConnection(EndpointId requested)
            throws EndpointConnector.ConnectionFailedException {
        long start = System.nanoTime();
        return switch (mode) {
            case PREDICTIVE -> predictiveCheckout(requested, start);
            case CIRCUIT_BREAKER -> circuitBreakerCheckout(requested, start);
            case REACTIVE -> reactiveCheckout(requested, start);
        };
    }

    private PapConnection predictiveCheckout(EndpointId requested, long start)
            throws EndpointConnector.ConnectionFailedException {
        Instant at = clock.instant();
        for (EndpointId id : registry.all()) {
            analyzer.analyze(id);
        }

        RoutingDecision decision = routingDecider.decide(requested, at);
        EndpointId selected = decision.selected();

        // Prefer warm connection when failing over to backup.
        if (warmPool.endpointId().equals(selected)) {
            var warmed = warmPool.poll();
            if (warmed.isPresent()) {
                PapConnection warm = warmed.get();
                IdleConnectionPool idle = idlePools.get(selected);
                if (idle != null) {
                    try {
                        idle.borrowExternal(warm);
                    } catch (EndpointConnector.ConnectionFailedException e) {
                        warm.destroy();
                        // fall through to normal acquire
                    }
                    if (warm.isOpen()) {
                        warm.setRoutingDecision(decision);
                        recordAttempt(selected, true, FailureType.NONE, start, 0);
                        metrics.recordCheckout(decision, true, true, elapsedMs(start, 0));
                        return warm;
                    }
                } else {
                    warm.destroy();
                }
            }
        }

        try {
            PapConnection connection = acquireAndRecord(selected, start);
            connection.setRoutingDecision(decision);
            metrics.recordCheckout(decision, true, false, connection.lastSimulatedLatencyMs());
            return connection;
        } catch (EndpointConnector.ConnectionFailedException firstFailure) {
            for (EndpointId candidate : registry.all()) {
                if (candidate.equals(selected)) {
                    continue;
                }
                try {
                    PapConnection connection = acquireAndRecord(candidate, start);
                    RiskScore score = scoreOrDefault(candidate, at);
                    Map<EndpointId, RiskScore> scores = new LinkedHashMap<>(decision.allScores());
                    scores.put(candidate, score);
                    RoutingDecision fallback = new RoutingDecision(
                            requested,
                            candidate,
                            RoutingDecision.Reason.DEGRADED_MODE,
                            decision.trigger(),
                            score,
                            scores);
                    connection.setRoutingDecision(fallback);
                    metrics.recordCheckout(fallback, true, false, connection.lastSimulatedLatencyMs());
                    return connection;
                } catch (EndpointConnector.ConnectionFailedException ignored) {
                    // try next
                }
            }
            metrics.recordCheckout(decision, false, false,
                    firstFailure.simulatedLatencyMs() > 0
                            ? firstFailure.simulatedLatencyMs()
                            : elapsedMs(start, 0));
            throw firstFailure;
        }
    }

    private PapConnection circuitBreakerCheckout(EndpointId requested, long start)
            throws EndpointConnector.ConnectionFailedException {
        Instant at = clock.instant();
        ZoneId zone = config.zoneId();
        refreshCircuitState(at);

        CircuitState state = circuitState.get();
        boolean tryPrimary = state == CircuitState.CLOSED
                || state == CircuitState.HALF_OPEN;

        if (tryPrimary) {
            try {
                PapConnection connection = acquireAndRecord(requested, start);
                onCircuitPrimarySuccess();
                RiskScore score = new RiskScore(requested, 0.1, 0, 0, 0, at.atZone(zone).getHour());
                RoutingDecision decision = new RoutingDecision(
                        requested,
                        requested,
                        RoutingDecision.Reason.PRIMARY_OK,
                        RoutingDecision.Trigger.NONE,
                        score,
                        Map.of(requested, score));
                connection.setRoutingDecision(decision);
                metrics.recordCheckout(decision, true, false, connection.lastSimulatedLatencyMs());
                return connection;
            } catch (EndpointConnector.ConnectionFailedException e) {
                onCircuitPrimaryFailure(at);
                // fall through to backup
            }
        }

        EndpointId backup = registry.firstBackup().orElse(null);
        if (backup == null) {
            RiskScore failScore = new RiskScore(
                    requested, 1.0, 1, 1, 1, at.atZone(zone).getHour());
            RoutingDecision failed = new RoutingDecision(
                    requested, requested, RoutingDecision.Reason.DEGRADED_MODE,
                    RoutingDecision.Trigger.SCORE, failScore, Map.of(requested, failScore));
            metrics.recordCheckout(failed, false, false, elapsedMs(start, 0));
            throw new EndpointConnector.ConnectionFailedException(
                    "circuit open, no backup", FailureType.UNKNOWN);
        }

        try {
            PapConnection connection = acquireAndRecord(backup, start);
            RiskScore score = new RiskScore(backup, 0.2, 0, 0, 0, at.atZone(zone).getHour());
            RoutingDecision decision = new RoutingDecision(
                    requested,
                    backup,
                    RoutingDecision.Reason.DEGRADED_MODE,
                    RoutingDecision.Trigger.NONE,
                    score,
                    Map.of(backup, score));
            connection.setRoutingDecision(decision);
            metrics.recordCheckout(decision, true, false, connection.lastSimulatedLatencyMs());
            return connection;
        } catch (EndpointConnector.ConnectionFailedException e) {
            RiskScore failScore = new RiskScore(
                    backup, 1.0, 1, 1, 1, at.atZone(zone).getHour());
            RoutingDecision failed = new RoutingDecision(
                    requested, backup, RoutingDecision.Reason.DEGRADED_MODE,
                    RoutingDecision.Trigger.SCORE, failScore, Map.of(backup, failScore));
            metrics.recordCheckout(failed, false, false,
                    e.simulatedLatencyMs() > 0 ? e.simulatedLatencyMs() : elapsedMs(start, 0));
            throw e;
        }
    }

    private void refreshCircuitState(Instant at) {
        Instant until = circuitOpenUntil.get();
        if (circuitState.get() == CircuitState.OPEN && until != null && !at.isBefore(until)) {
            circuitState.set(CircuitState.HALF_OPEN);
        }
    }

    private void onCircuitPrimarySuccess() {
        consecutivePrimaryFailures.set(0);
        circuitState.set(CircuitState.CLOSED);
        circuitOpenUntil.set(null);
    }

    private void onCircuitPrimaryFailure(Instant at) {
        if (circuitState.get() == CircuitState.HALF_OPEN) {
            circuitState.set(CircuitState.OPEN);
            circuitOpenUntil.set(at.plusSeconds(60));
            consecutivePrimaryFailures.set(3);
            return;
        }
        int fails = consecutivePrimaryFailures.incrementAndGet();
        if (fails >= 3) {
            circuitState.set(CircuitState.OPEN);
            circuitOpenUntil.set(at.plusSeconds(60));
        }
    }

    private PapConnection reactiveCheckout(EndpointId requested, long start)
            throws EndpointConnector.ConnectionFailedException {
        EndpointConnector.ConnectionFailedException last = null;
        Instant at = clock.instant();
        ZoneId zone = config.zoneId();

        for (EndpointId candidate : ordered(requested)) {
            try {
                PapConnection connection = acquireAndRecord(candidate, start);
                RiskScore score = new RiskScore(
                        candidate,
                        candidate.equals(requested) ? 0.1 : 0.9,
                        0, 0, 0,
                        at.atZone(zone).getHour());
                Map<EndpointId, RiskScore> scores = Map.of(candidate, score);
                RoutingDecision decision = new RoutingDecision(
                        requested,
                        candidate,
                        candidate.equals(requested)
                                ? RoutingDecision.Reason.PRIMARY_OK
                                : RoutingDecision.Reason.DEGRADED_MODE,
                        RoutingDecision.Trigger.NONE,
                        score,
                        scores);
                connection.setRoutingDecision(decision);
                metrics.recordCheckout(decision, true, false, connection.lastSimulatedLatencyMs());
                return connection;
            } catch (EndpointConnector.ConnectionFailedException e) {
                last = e;
            }
        }

        RiskScore failScore = new RiskScore(
                requested, 1.0, 1, 1, 1, at.atZone(zone).getHour());
        RoutingDecision failed = new RoutingDecision(
                requested,
                requested,
                RoutingDecision.Reason.DEGRADED_MODE,
                RoutingDecision.Trigger.SCORE,
                failScore,
                Map.of(requested, failScore));
        metrics.recordCheckout(failed, false, false,
                last != null && last.simulatedLatencyMs() > 0
                        ? last.simulatedLatencyMs()
                        : elapsedMs(start, 0));
        throw last == null
                ? new EndpointConnector.ConnectionFailedException("no endpoints", FailureType.UNKNOWN)
                : last;
    }

    private Iterable<EndpointId> ordered(EndpointId requested) {
        java.util.List<EndpointId> order = new java.util.ArrayList<>();
        order.add(requested);
        for (EndpointId id : registry.all()) {
            if (!id.equals(requested)) {
                order.add(id);
            }
        }
        return order;
    }

    private RiskScore scoreOrDefault(EndpointId endpointId, Instant at) {
        var profile = analyzer.getProfile(endpointId);
        if (profile == null || profile.sampleCount() == 0) {
            return predictionEngine.scoreUnknown(endpointId, at);
        }
        return predictionEngine.score(profile, at);
    }

    private PapConnection acquireAndRecord(EndpointId endpointId, long startNanos)
            throws EndpointConnector.ConnectionFailedException {
        IdleConnectionPool idle = idlePools.get(endpointId);
        if (idle == null) {
            throw new EndpointConnector.ConnectionFailedException(
                    "no pool for " + endpointId, FailureType.UNKNOWN);
        }
        try {
            long beforePhysical = idle.physicalConnects();
            long beforeReuse = idle.reuseHits();
            PapConnection connection = idle.acquire();
            if (idle.physicalConnects() > beforePhysical) {
                metrics.recordPhysicalConnect();
                EndpointConnector connector = connectors.get(endpointId);
                if (connector instanceof FlakyEndpointConnector flaky) {
                    connection.setLastSimulatedLatencyMs(flaky.lastSimulatedLatencyMs());
                }
            }
            if (idle.reuseHits() > beforeReuse) {
                metrics.recordReuseHit();
            }
            recordAttempt(endpointId, true, FailureType.NONE, startNanos,
                    connection.lastSimulatedLatencyMs());
            return connection;
        } catch (EndpointConnector.ConnectionFailedException e) {
            long sim = 0;
            EndpointConnector connector = connectors.get(endpointId);
            if (connector instanceof FlakyEndpointConnector flaky) {
                sim = flaky.lastSimulatedLatencyMs();
                e.setSimulatedLatencyMs(sim);
            }
            recordAttempt(endpointId, false, e.failureType(), startNanos, sim);
            analyzer.analyze(endpointId);
            metrics.recordConnectFailure();
            throw e;
        }
    }

    private void recordAttempt(
            EndpointId endpointId,
            boolean success,
            FailureType type,
            long startNanos,
            long simulatedLatencyMs) {
        Instant started = clock.instant();
        long duration = simulatedLatencyMs > 0
                ? simulatedLatencyMs
                : Math.max(0, elapsedMs(startNanos, 0));
        ConnectionAttempt attempt = success
                ? ConnectionAttempt.success(endpointId, started, duration)
                : ConnectionAttempt.failure(endpointId, started, AttemptOutcome.FAILURE, type, duration);
        historyStore.record(attempt);
        analyzer.observe(attempt);
        resetDetector.observe(attempt);
    }

    private static long elapsedMs(long startNanos, long simulatedFallback) {
        if (simulatedFallback > 0) {
            return simulatedFallback;
        }
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Analyzer + pre-warmer tick (including recovery probes). */
    public void backgroundTick() {
        analyzer.analyzeAll();
        if (mode == Mode.PREDICTIVE) {
            preWarmer.tick();
        }
    }

    private void backgroundTickSafe() {
        try {
            backgroundTick();
        } catch (RuntimeException ignored) {
            // keep scheduler alive
        }
    }

    public FailureHistoryStore historyStore() {
        return historyStore;
    }

    public PatternAnalyzer analyzer() {
        return analyzer;
    }

    public ResetPatternDetector resetDetector() {
        return resetDetector;
    }

    public RoutingDecider routingDecider() {
        return routingDecider;
    }

    public PredictionEngine predictionEngine() {
        return predictionEngine;
    }

    public BackupPreWarmer preWarmer() {
        return preWarmer;
    }

    public PoolMetrics metrics() {
        return metrics;
    }

    public PoolConfig config() {
        return config;
    }

    public MonitoringThreadFactory threadFactory() {
        return threadFactory;
    }

    public boolean predictiveMode() {
        return mode == Mode.PREDICTIVE;
    }

    public Mode mode() {
        return mode;
    }

    public EndpointRegistry registry() {
        return registry;
    }

    public IdleConnectionPool idlePool(EndpointId endpointId) {
        return idlePools.get(endpointId);
    }

    public long physicalConnects() {
        long total = 0;
        for (IdleConnectionPool p : idlePools.values()) {
            total += p.physicalConnects();
        }
        return total;
    }

    public long reuseHits() {
        long total = 0;
        for (IdleConnectionPool p : idlePools.values()) {
            total += p.reuseHits();
        }
        return total;
    }

    public void start() {
        backgroundTick();
    }

    public void shutdown() {
        close();
    }

    @Override
    public void close() {
        preWarmer.close();
        for (IdleConnectionPool pool : idlePools.values()) {
            pool.drainAndClose();
        }
        scheduler.shutdownNow();
    }
}
