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
import java.util.function.DoubleSupplier;

/**
 * Layer 2 — Predictive rerouting with LIVE threshold + hot-hour rule.
 *
 * Failover if:
 * <ul>
 *   <li>weighted risk ≥ threshold, OR</li>
 *   <li>hot hour: samplesAtHour ≥ minSamples AND failureRateAtHour ≥ hotHourRate</li>
 * </ul>
 * Hot-hour alone can preempt before any live failure in a known bad window.
 */
public final class RoutingDecider {

    private final EndpointRegistry registry;
    private final PatternAnalyzer analyzer;
    private final PredictionEngine predictionEngine;
    private final DoubleSupplier thresholdSupplier;
    private final int hotHourMinSamples;
    private final double hotHourMinRate;

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine) {
        this(registry, analyzer, predictionEngine, () -> 0.55, 5, 0.50);
    }

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            double highRiskThreshold) {
        this(registry, analyzer, predictionEngine, () -> highRiskThreshold, 5, 0.50);
    }

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            PoolConfig config) {
        this(
                registry,
                analyzer,
                predictionEngine,
                config::highRiskThreshold,
                config.hotHourMinSamples(),
                config.hotHourMinRate());
    }

    public RoutingDecider(
            EndpointRegistry registry,
            PatternAnalyzer analyzer,
            PredictionEngine predictionEngine,
            DoubleSupplier thresholdSupplier,
            int hotHourMinSamples,
            double hotHourMinRate) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.analyzer = Objects.requireNonNull(analyzer, "analyzer");
        this.predictionEngine = Objects.requireNonNull(predictionEngine, "predictionEngine");
        this.thresholdSupplier = Objects.requireNonNull(thresholdSupplier, "thresholdSupplier");
        this.hotHourMinSamples = hotHourMinSamples;
        this.hotHourMinRate = hotHourMinRate;
    }

    public double highRiskThreshold() {
        return thresholdSupplier.getAsDouble();
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

        double threshold = thresholdSupplier.getAsDouble();
        Map<EndpointId, RiskScore> scores = scoreRegistered(at);
        RiskScore requestedScore = scores.get(requested);
        EndpointRiskProfile requestedProfile = resolveProfile(requested);

        if (registry.all().size() == 1) {
            return new RoutingDecision(
                    requested, requested, RoutingDecision.Reason.SINGLE_ENDPOINT, requestedScore, scores);
        }

        boolean hotHour = requestedProfile != null
                && requestedProfile.isHotHour(requestedScore.hourOfDay(), hotHourMinSamples, hotHourMinRate);
        boolean highRisk = requestedScore.isHighRisk(threshold) || hotHour;

        if (!highRisk) {
            return new RoutingDecision(
                    requested, requested, RoutingDecision.Reason.PRIMARY_OK, requestedScore, scores);
        }

        EndpointId bestHealthy = null;
        RiskScore bestHealthyScore = null;
        EndpointId leastBad = requested;
        RiskScore leastBadScore = requestedScore;

        for (EndpointId candidate : registry.all()) {
            RiskScore score = scores.get(candidate);
            EndpointRiskProfile profile = resolveProfile(candidate);
            boolean candidateHot = profile != null
                    && profile.isHotHour(score.hourOfDay(), hotHourMinSamples, hotHourMinRate);
            boolean candidateHigh = score.isHighRisk(threshold) || candidateHot;

            boolean better = score.score() < leastBadScore.score()
                    || (score.score() == leastBadScore.score()
                    && !candidate.equals(requested)
                    && leastBad.equals(requested));
            if (better) {
                leastBad = candidate;
                leastBadScore = score;
            }
            if (!candidateHigh) {
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
                    bestHealthyScore,
                    scores);
        }

        return new RoutingDecision(
                requested,
                leastBad,
                RoutingDecision.Reason.DEGRADED_MODE,
                leastBadScore,
                scores);
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
