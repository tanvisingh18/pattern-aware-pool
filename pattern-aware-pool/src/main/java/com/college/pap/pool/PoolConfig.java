package com.college.pap.pool;

import com.college.pap.prediction.RiskWeights;

import java.time.ZoneId;
import java.util.Objects;

/**
 * Tunable pool configuration. All fields are volatile so RMI updates
 * are visible immediately to RoutingDecider / PredictionEngine / PreWarmer.
 */
public final class PoolConfig {
    private volatile RiskWeights weights = RiskWeights.defaults();
    private volatile double highRiskThreshold = 0.55;
    private volatile int preWarmLeadMinutes = 10;
    private volatile int warmPoolSize = 5;
    private volatile int historyCapacity = 2000;
    private volatile long analyzerPeriodSeconds = 5;
    private volatile long preWarmPeriodSeconds = 2;
    private volatile int hotHourMinSamples = 5;
    private volatile double hotHourThreshold = 0.50;
    private volatile int clusterAvoidRun = 3;
    private volatile int maxPoolSizePerEndpoint = 10;
    private volatile long recoveryProbeSeconds = 30;
    private volatile boolean reuseEnabled = true;
    private volatile ZoneId zoneId = ZoneId.systemDefault();

    public RiskWeights weights() {
        return weights;
    }

    public void setWeights(RiskWeights weights) {
        this.weights = Objects.requireNonNull(weights);
    }

    public double highRiskThreshold() {
        return highRiskThreshold;
    }

    public void setHighRiskThreshold(double highRiskThreshold) {
        if (highRiskThreshold < 0 || highRiskThreshold > 1) {
            throw new IllegalArgumentException("threshold must be in [0,1]");
        }
        this.highRiskThreshold = highRiskThreshold;
    }

    public int preWarmLeadMinutes() {
        return preWarmLeadMinutes;
    }

    public void setPreWarmLeadMinutes(int preWarmLeadMinutes) {
        if (preWarmLeadMinutes < 0) {
            throw new IllegalArgumentException("lead minutes must be >= 0");
        }
        this.preWarmLeadMinutes = preWarmLeadMinutes;
    }

    public int warmPoolSize() {
        return warmPoolSize;
    }

    public void setWarmPoolSize(int warmPoolSize) {
        this.warmPoolSize = warmPoolSize;
    }

    public int historyCapacity() {
        return historyCapacity;
    }

    public void setHistoryCapacity(int historyCapacity) {
        this.historyCapacity = historyCapacity;
    }

    public long analyzerPeriodSeconds() {
        return analyzerPeriodSeconds;
    }

    public void setAnalyzerPeriodSeconds(long analyzerPeriodSeconds) {
        this.analyzerPeriodSeconds = analyzerPeriodSeconds;
    }

    public long preWarmPeriodSeconds() {
        return preWarmPeriodSeconds;
    }

    public void setPreWarmPeriodSeconds(long preWarmPeriodSeconds) {
        this.preWarmPeriodSeconds = preWarmPeriodSeconds;
    }

    public int hotHourMinSamples() {
        return hotHourMinSamples;
    }

    public void setHotHourMinSamples(int hotHourMinSamples) {
        this.hotHourMinSamples = Math.max(1, hotHourMinSamples);
    }

    /** Preferred name for the hot-hour failure-rate threshold. */
    public double hotHourThreshold() {
        return hotHourThreshold;
    }

    public void setHotHourThreshold(double hotHourThreshold) {
        this.hotHourThreshold = Math.max(0.0, Math.min(1.0, hotHourThreshold));
    }

    /** Alias kept for older call sites. */
    public double hotHourMinRate() {
        return hotHourThreshold;
    }

    public void setHotHourMinRate(double hotHourMinRate) {
        setHotHourThreshold(hotHourMinRate);
    }

    public int clusterAvoidRun() {
        return clusterAvoidRun;
    }

    public void setClusterAvoidRun(int clusterAvoidRun) {
        this.clusterAvoidRun = Math.max(1, clusterAvoidRun);
    }

    public int maxPoolSizePerEndpoint() {
        return maxPoolSizePerEndpoint;
    }

    public void setMaxPoolSizePerEndpoint(int maxPoolSizePerEndpoint) {
        this.maxPoolSizePerEndpoint = Math.max(1, maxPoolSizePerEndpoint);
    }

    public long recoveryProbeSeconds() {
        return recoveryProbeSeconds;
    }

    public void setRecoveryProbeSeconds(long recoveryProbeSeconds) {
        this.recoveryProbeSeconds = Math.max(1, recoveryProbeSeconds);
    }

    public boolean reuseEnabled() {
        return reuseEnabled;
    }

    public void setReuseEnabled(boolean reuseEnabled) {
        this.reuseEnabled = reuseEnabled;
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public void setZoneId(ZoneId zoneId) {
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
    }
}
