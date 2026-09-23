package com.college.pap.prediction;

import com.college.pap.model.ClusterState;
import com.college.pap.model.EndpointId;
import com.college.pap.model.EndpointRiskProfile;
import com.college.pap.pool.PoolConfig;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Layer 2 — Prediction.
 * Reads weights LIVE from {@link PoolConfig} on every call (RMI retune takes effect).
 */
public final class PredictionEngine {

    private final Supplier<RiskWeights> weightsSupplier;
    private final Clock clock;
    private final ZoneId zoneId;

    public PredictionEngine() {
        this(() -> RiskWeights.defaults(), Clock.systemUTC(), ZoneId.systemDefault());
    }

    public PredictionEngine(RiskWeights weights) {
        this(() -> weights, Clock.systemUTC(), ZoneId.systemDefault());
    }

    public PredictionEngine(RiskWeights weights, Clock clock) {
        this(() -> weights, clock, ZoneId.systemDefault());
    }

    public PredictionEngine(PoolConfig config, Clock clock) {
        this(config::weights, clock, ZoneId.systemDefault());
    }

    public PredictionEngine(Supplier<RiskWeights> weightsSupplier, Clock clock, ZoneId zoneId) {
        this.weightsSupplier = Objects.requireNonNull(weightsSupplier, "weightsSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }

    public RiskWeights weights() {
        return weightsSupplier.get();
    }

    public RiskScore score(EndpointRiskProfile profile) {
        return score(profile, clock.instant());
    }

    public RiskScore score(EndpointRiskProfile profile, Instant at) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(at, "at");

        RiskWeights weights = weightsSupplier.get();
        int hour = at.atZone(zoneId).getHour();
        double tod = profile.failureRateAtHour(hour);
        double cluster = profile.clusterState().clusterPenalty();
        double recent = profile.recentFailureRate();

        double raw = weights.alpha() * tod
                + weights.beta() * cluster
                + weights.gamma() * recent;

        return new RiskScore(profile.endpointId(), raw, tod, cluster, recent, hour);
    }

    public RiskScore scoreUnknown(EndpointId endpointId, Instant at) {
        Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(at, "at");
        int hour = at.atZone(zoneId).getHour();
        return new RiskScore(endpointId, 0.0, 0.0, 0.0, 0.0, hour);
    }

    public Map<EndpointId, RiskScore> scoreAll(Map<EndpointId, EndpointRiskProfile> profiles, Instant at) {
        Map<EndpointId, RiskScore> scores = new LinkedHashMap<>();
        for (Map.Entry<EndpointId, EndpointRiskProfile> entry : profiles.entrySet()) {
            scores.put(entry.getKey(), score(entry.getValue(), at));
        }
        return scores;
    }

    public static EndpointRiskProfile emptyProfile(EndpointId endpointId) {
        return new EndpointRiskProfile(
                endpointId,
                new double[24],
                new int[24],
                0.0,
                ClusterState.idle(),
                Instant.EPOCH,
                0);
    }
}
