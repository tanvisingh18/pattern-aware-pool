package com.college.pap.model;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * Learned risk profile for one endpoint.
 * Includes per-hour rates AND sample counts so hot-hour failover
 * can require a minimum evidence threshold.
 */
public final class EndpointRiskProfile {
    private final EndpointId endpointId;
    private final double[] hourlyFailureRates;
    private final double[] hourlyPlainFailureRates;
    private final int[] hourlySampleCounts;
    private final double recentFailureRate;
    private final ClusterState clusterState;
    private final Instant computedAt;
    private final long sampleCount;

    public EndpointRiskProfile(
            EndpointId endpointId,
            double[] hourlyFailureRates,
            double[] hourlyPlainFailureRates,
            int[] hourlySampleCounts,
            double recentFailureRate,
            ClusterState clusterState,
            Instant computedAt,
            long sampleCount) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        Objects.requireNonNull(hourlyFailureRates, "hourlyFailureRates");
        Objects.requireNonNull(hourlyPlainFailureRates, "hourlyPlainFailureRates");
        Objects.requireNonNull(hourlySampleCounts, "hourlySampleCounts");
        if (hourlyFailureRates.length != 24
                || hourlyPlainFailureRates.length != 24
                || hourlySampleCounts.length != 24) {
            throw new IllegalArgumentException("hourly arrays must have length 24");
        }
        this.hourlyFailureRates = Arrays.copyOf(hourlyFailureRates, 24);
        this.hourlyPlainFailureRates = Arrays.copyOf(hourlyPlainFailureRates, 24);
        this.hourlySampleCounts = Arrays.copyOf(hourlySampleCounts, 24);
        this.recentFailureRate = clamp01(recentFailureRate);
        this.clusterState = Objects.requireNonNull(clusterState, "clusterState");
        this.computedAt = Objects.requireNonNull(computedAt, "computedAt");
        this.sampleCount = sampleCount;
    }

    public EndpointRiskProfile(
            EndpointId endpointId,
            double[] hourlyFailureRates,
            int[] hourlySampleCounts,
            double recentFailureRate,
            ClusterState clusterState,
            Instant computedAt,
            long sampleCount) {
        this(
                endpointId,
                hourlyFailureRates,
                hourlyFailureRates,
                hourlySampleCounts,
                recentFailureRate,
                clusterState,
                computedAt,
                sampleCount);
    }

    /** Backward-compatible constructor (zeros for sample counts). */
    public EndpointRiskProfile(
            EndpointId endpointId,
            double[] hourlyFailureRates,
            double recentFailureRate,
            ClusterState clusterState,
            Instant computedAt,
            long sampleCount) {
        this(endpointId, hourlyFailureRates, new int[24], recentFailureRate, clusterState, computedAt, sampleCount);
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public double failureRateAtHour(int hour) {
        checkHour(hour);
        return hourlyFailureRates[hour];
    }

    /** Empirical failures/samples — used for hot-hour gating (stable vs EWMA spikes). */
    public double plainFailureRateAtHour(int hour) {
        checkHour(hour);
        return hourlyPlainFailureRates[hour];
    }

    public int samplesAtHour(int hour) {
        checkHour(hour);
        return hourlySampleCounts[hour];
    }

    /**
     * Hot-hour signal: enough samples and elevated EWMA failure rate.
     * Plain empirical rate is retained for display only.
     */
    public boolean isHotHour(int hour, int minSamples, double minRate) {
        return samplesAtHour(hour) >= minSamples && failureRateAtHour(hour) >= minRate;
    }

    public double[] hourlyFailureRates() {
        return Arrays.copyOf(hourlyFailureRates, 24);
    }

    public int[] hourlySampleCounts() {
        return Arrays.copyOf(hourlySampleCounts, 24);
    }

    public double recentFailureRate() {
        return recentFailureRate;
    }

    public ClusterState clusterState() {
        return clusterState;
    }

    public Instant computedAt() {
        return computedAt;
    }

    public long sampleCount() {
        return sampleCount;
    }

    private static void checkHour(int hour) {
        if (hour < 0 || hour > 23) {
            throw new IllegalArgumentException("hour must be 0..23");
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    @Override
    public String toString() {
        StringBuilder hours = new StringBuilder();
        for (int h = 0; h < 24; h++) {
            if (hourlySampleCounts[h] > 0 && hourlyPlainFailureRates[h] > 0.05) {
                if (hours.length() > 0) {
                    hours.append(", ");
                }
                hours.append(String.format("%02d:00=%.0f%%(n=%d)", h, hourlyPlainFailureRates[h] * 100, hourlySampleCounts[h]));
            }
        }
        if (hours.length() == 0) {
            hours.append("no elevated hours");
        }
        return "EndpointRiskProfile{"
                + endpointId
                + ", samples=" + sampleCount
                + ", recentFail=" + String.format("%.0f%%", recentFailureRate * 100)
                + ", hotHours=[" + hours + "]"
                + ", " + clusterState
                + '}';
    }
}
