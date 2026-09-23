package com.college.pap.prediction;

import com.college.pap.model.EndpointId;

import java.util.Objects;

/**
 * Scored risk for one endpoint at a point in time.
 * Score is in [0.0, 1.0] — higher means more likely to fail.
 */
public final class RiskScore {
    private final EndpointId endpointId;
    private final double score;
    private final double timeOfDayRate;
    private final double clusterPenalty;
    private final double recentFailureRate;
    private final int hourOfDay;

    public RiskScore(
            EndpointId endpointId,
            double score,
            double timeOfDayRate,
            double clusterPenalty,
            double recentFailureRate,
            int hourOfDay) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.score = clamp01(score);
        this.timeOfDayRate = clamp01(timeOfDayRate);
        this.clusterPenalty = clamp01(clusterPenalty);
        this.recentFailureRate = clamp01(recentFailureRate);
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("hourOfDay must be 0..23");
        }
        this.hourOfDay = hourOfDay;
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public double score() {
        return score;
    }

    public double timeOfDayRate() {
        return timeOfDayRate;
    }

    public double clusterPenalty() {
        return clusterPenalty;
    }

    public double recentFailureRate() {
        return recentFailureRate;
    }

    public int hourOfDay() {
        return hourOfDay;
    }

    public boolean isHighRisk(double threshold) {
        return score >= threshold;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public String toString() {
        return String.format(
                "RiskScore{%s @ %02d:00 = %.2f (tod=%.2f, cluster=%.2f, recent=%.2f)}",
                endpointId, hourOfDay, score, timeOfDayRate, clusterPenalty, recentFailureRate);
    }
}
