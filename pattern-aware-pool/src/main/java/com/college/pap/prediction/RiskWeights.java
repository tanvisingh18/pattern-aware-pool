package com.college.pap.prediction;

/**
 * Tunable weights for the Layer-2 risk scoring function:
 *
 * <pre>
 * risk_score = α × time_of_day_failure_rate
 *            + β × cluster_penalty
 *            + γ × recent_failure_rate
 * </pre>
 *
 * Exposed later via RMI so a remote console can retune without restart.
 */
public final class RiskWeights {
    private final double alpha;
    private final double beta;
    private final double gamma;

    public RiskWeights(double alpha, double beta, double gamma) {
        if (alpha < 0 || beta < 0 || gamma < 0) {
            throw new IllegalArgumentException("weights must be >= 0");
        }
        double sum = alpha + beta + gamma;
        if (sum <= 0) {
            throw new IllegalArgumentException("at least one weight must be > 0");
        }
        this.alpha = alpha;
        this.beta = beta;
        this.gamma = gamma;
    }

    /** Sensible defaults emphasising time-of-day + clustering. */
    public static RiskWeights defaults() {
        return new RiskWeights(0.45, 0.35, 0.20);
    }

    public double alpha() {
        return alpha;
    }

    public double beta() {
        return beta;
    }

    public double gamma() {
        return gamma;
    }

    @Override
    public String toString() {
        return String.format("RiskWeights{α=%.2f, β=%.2f, γ=%.2f}", alpha, beta, gamma);
    }
}
