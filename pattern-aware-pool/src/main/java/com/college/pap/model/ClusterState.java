package com.college.pap.model;

/**
 * Snapshot of recent failure burstiness for one endpoint.
 * If failures arrive in runs (e.g. 3–5 in a row), currentRunLength rises
 * and Layer 2 can raise the cluster penalty before the next attempt.
 */
public final class ClusterState {
    private final int currentRunLength;
    private final double averageFailureRunLength;
    private final boolean inFailureCluster;

    public ClusterState(int currentRunLength, double averageFailureRunLength, boolean inFailureCluster) {
        if (currentRunLength < 0) {
            throw new IllegalArgumentException("currentRunLength must be >= 0");
        }
        if (averageFailureRunLength < 0) {
            throw new IllegalArgumentException("averageFailureRunLength must be >= 0");
        }
        this.currentRunLength = currentRunLength;
        this.averageFailureRunLength = averageFailureRunLength;
        this.inFailureCluster = inFailureCluster;
    }

    public static ClusterState idle() {
        return new ClusterState(0, 0.0, false);
    }

    public int currentRunLength() {
        return currentRunLength;
    }

    public double averageFailureRunLength() {
        return averageFailureRunLength;
    }

    public boolean inFailureCluster() {
        return inFailureCluster;
    }

    /**
     * Penalty in [0, 1] used by PredictionEngine (Layer 2).
     * Longer current runs and historically longer average runs → higher penalty.
     */
    public double clusterPenalty() {
        if (!inFailureCluster && currentRunLength == 0) {
            return 0.0;
        }
        double currentFactor = Math.min(1.0, currentRunLength / 5.0);
        double historyFactor = Math.min(1.0, averageFailureRunLength / 5.0);
        return Math.min(1.0, 0.7 * currentFactor + 0.3 * historyFactor);
    }

    @Override
    public String toString() {
        return "ClusterState{run=" + currentRunLength
                + ", avgRun=" + String.format("%.2f", averageFailureRunLength)
                + ", inCluster=" + inFailureCluster + '}';
    }
}
