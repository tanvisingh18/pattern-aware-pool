package com.college.pap.routing;

import com.college.pap.analysis.PatternAnalyzer;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.pool.PoolConfig;
import com.college.pap.prediction.PredictionEngine;
import com.college.pap.prediction.RiskScore;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Layer 2 — Predictive rerouting with LIVE threshold + hot-hour + live-cluster rules.
 *
 * Failover if ANY of:
 * <ul>
 *   <li>weighted risk ≥ highRiskThreshold</li>
 *   <li>hot hour: samples ≥ minSamples AND failureRate ≥ hotHourThreshold</li>
 *   <li>live cluster: currentRunLength ≥ clusterAvoidRun</li>
 * </ul>
 */
public final class RoutingDecider {

    private final EndpointRegistry registry;
    private final PatternAnalyzer analyzer;
    private final PredictionEngine predictionEngine;
    private final Supplier<PoolConfig> configSupplier;

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine) {
        this(registry, analyzer, predictionEngine, new PoolConfig());
    }

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            double highRiskThreshold) {
        this(registry, analyzer, predictionEngine, configWithThreshold(highRiskThreshold));
    }

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            PoolConfig config) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.predictionEngine = Objects.requireNonNull(predictionEngine, "predictionEngine");
        Objects.requireNonNull(config, "config");
        this.configSupplier = () -> config;
    }

    private static PoolConfig configWithThreshold(double highRiskThreshold) {
        PoolConfig cfg = new PoolConfig();
        cfg.setHighRiskThreshold(highRiskThreshold);
        return cfg;
    }

    public double highRiskThreshold() {
        return configSupplier.get().highRiskThreshold();
    }

    public PoolConfig config() {
        return configSupplier.get();
    }

    public RoutingDecision decide() {
        return decide(registry.primary(), Instant.now());
    }

    public RoutingDecision decide(EndpointId requested) {
        return decide(requested, Instant.now());
    }

    public RoutingDecision decide(EndpointId requested, Instant at) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(at, "at");
        if (!registry.contains(requested)) {
            throw new IllegalArgumentException("unknown endpoint: " + requested);
        }

        PoolConfig config = configSupplier.get();
        Map<EndpointId, RiskScore> scores = scoreRegistered(at);
        RiskScore requestedScore = scores.get(requested);
        EndpointRiskProfile requestedProfile = resolveProfile(requested);

        if (registry.all().size() == 1) {
            return new RoutingDecision(
                    requested,
                    requested,
                    RoutingDecision.Reason.SINGLE_ENDPOINT,
                    RoutingDecision.Trigger.NONE,
                    requestedScore,
                    scores);
        }

        RoutingDecision.Trigger trigger = classifyTrigger(requestedProfile, requestedScore, config);
        boolean avoid = trigger != RoutingDecision.Trigger.NONE;

        if (!avoid) {
            return new RoutingDecision(
                    requested,
                    requested,
                    RoutingDecision.Reason.PRIMARY_OK,
                    RoutingDecision.Trigger.NONE,
                    requestedScore,
                    scores);
        }

        EndpointId bestHealthy = null;
        RiskScore bestHealthyScore = null;
        EndpointId leastBad = requested;
        RiskScore leastBadScore = requestedScore;

        for (EndpointId candidate : registry.all()) {
            RiskScore score = scores.get(candidate);
            EndpointRiskProfile profile = resolveProfile(candidate);
            RoutingDecision.Trigger candidateTrigger = classifyTrigger(profile, score, config);
            boolean candidateAvoid = candidateTrigger != RoutingDecision.Trigger.NONE;

            boolean better = score.score() < leastBadScore.score()
                    || (score.score() == leastBadScore.score()
                    && !candidate.equals(requested)
                    && leastBad.equals(requested));
            if (better) {
                leastBad = candidate;
                leastBadScore = score;
            }
            if (!candidateAvoid) {
                if (bestHealthyScore == null || score.score() < bestHealthyScore.score()) {
                    bestHealthy = candidate;
                    bestHealthyScore = score;
                }
            }
        }

        if (bestHealthy != null) {
            return new RoutingDecision(
                    requested,
                    bestHealthy,
                    RoutingDecision.Reason.PREEMPTIVE_FAILOVER,
                    trigger,
                    bestHealthyScore,
                    scores);
        }

        return new RoutingDecision(
                requested,
                leastBad,
                RoutingDecision.Reason.DEGRADED_MODE,
                trigger,
                leastBadScore,
                scores);
    }

    /**
     * Attribution order: HOT_HOUR → LIVE_CLUSTER → SCORE (first match wins).
     * Hot-hour is checked first so known bad windows report HOT_HOUR even when
     * the composite score is also elevated.
     */
    static RoutingDecision.Trigger classifyTrigger(
            EndpointRiskProfile profile, RiskScore score, PoolConfig config) {
        if (profile != null && HotHourRules.isHot(profile, score.hourOfDay(), config)) {
            return RoutingDecision.Trigger.HOT_HOUR;
        }
        if (profile != null
                && profile.clusterState().currentRunLength() >= config.clusterAvoidRun()) {
            return RoutingDecision.Trigger.LIVE_CLUSTER;
        }
        if (score.isHighRisk(config.highRiskThreshold())) {
            return RoutingDecision.Trigger.SCORE;
        }
        return RoutingDecision.Trigger.NONE;
    }

    private Map<EndpointId, RiskScore> scoreRegistered(Instant at) {
        Map<EndpointId, RiskScore> scores = new LinkedHashMap<>();
        for (EndpointId endpointId : registry.all()) {
            EndpointRiskProfile profile = resolveProfile(endpointId);
            if (profile == null || profile.sampleCount() == 0) {
                scores.put(endpointId, predictionEngine.scoreUnknown(endpointId, at));
            } else {
                scores.put(endpointId, predictionEngine.score(profile, at));
            }
        }
        return scores;
    }

    private EndpointRiskProfile resolveProfile(EndpointId endpointId) {
        EndpointRiskProfile cached = analyzer.getProfile(endpointId);
        if (cached != null) {
            return cached;
        }
        return analyzer.analyze(endpointId);
    }
}
