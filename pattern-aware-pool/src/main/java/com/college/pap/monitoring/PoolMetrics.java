package com.college.pap.monitoring;

import com.college.pap.routing.RoutingDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** In-memory observability counters for demos, experiments, and RMI. */
public final class PoolMetrics {
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successfulCheckouts = new LongAdder();
    private final LongAdder failedCheckouts = new LongAdder();
    private final LongAdder preemptiveFailovers = new LongAdder();
    private final LongAdder primaryOk = new LongAdder();
    private final LongAdder degradedMode = new LongAdder();
    private final LongAdder warmHits = new LongAdder();
    private final LongAdder coldCreates = new LongAdder();
    private final LongAdder preWarmEvents = new LongAdder();
    private final LongAdder connectFailures = new LongAdder();
    private final LongAdder recoveryProbesOk = new LongAdder();
    private final LongAdder recoveryProbesFail = new LongAdder();
    private final LongAdder physicalConnects = new LongAdder();
    private final LongAdder primaryConnectAttempts = new LongAdder();
    private final LongAdder reuseHits = new LongAdder();
    private final AtomicLong totalCheckoutLatencyMs = new AtomicLong();
    private final ConcurrentLinkedQueue<Long> checkoutLatenciesMs = new ConcurrentLinkedQueue<>();
    private final Map<String, LongAdder> selectedEndpointCounts = new ConcurrentHashMap<>();

    public void recordConnectFailure() {
        connectFailures.increment();
    }

    public void recordPhysicalConnect() {
        physicalConnects.increment();
    }

    public void recordPrimaryConnectAttempt() {
        primaryConnectAttempts.increment();
    }

    public void recordReuseHit() {
        reuseHits.increment();
    }

    public void recordRecoveryProbe(boolean success) {
        if (success) {
            recoveryProbesOk.increment();
        } else {
            recoveryProbesFail.increment();
        }
    }

    public void recordCheckout(RoutingDecision decision, boolean success, boolean usedWarm, long latencyMs) {
        totalRequests.increment();
        long lat = Math.max(0, latencyMs);
        totalCheckoutLatencyMs.addAndGet(lat);
        checkoutLatenciesMs.add(lat);
        if (success) {
            successfulCheckouts.increment();
        } else {
            failedCheckouts.increment();
        }
        if (usedWarm) {
            warmHits.increment();
        } else {
            coldCreates.increment();
        }
        switch (decision.reason()) {
            case PREEMPTIVE_FAILOVER -> preemptiveFailovers.increment();
            case PRIMARY_OK, SINGLE_ENDPOINT -> primaryOk.increment();
            case DEGRADED_MODE -> degradedMode.increment();
        }
        selectedEndpointCounts
                .computeIfAbsent(decision.selected().value(), k -> new LongAdder())
                .increment();
    }

    public void recordPreWarm() {
        preWarmEvents.increment();
    }

    public long totalRequests() {
        return totalRequests.sum();
    }

    public long successfulCheckouts() {
        return successfulCheckouts.sum();
    }

    public long failedCheckouts() {
        return failedCheckouts.sum();
    }

    public long preemptiveFailovers() {
        return preemptiveFailovers.sum();
    }

    public long primaryOk() {
        return primaryOk.sum();
    }

    public long degradedMode() {
        return degradedMode.sum();
    }

    public long warmHits() {
        return warmHits.sum();
    }

    public long coldCreates() {
        return coldCreates.sum();
    }

    public long preWarmEvents() {
        return preWarmEvents.sum();
    }

    public long connectFailures() {
        return connectFailures.sum();
    }

    public long physicalConnects() {
        return physicalConnects.sum();
    }

    public long primaryConnectAttempts() {
        return primaryConnectAttempts.sum();
    }

    /** User-facing checkout failures (excludes pool-exhaustion and probe accounting). */
    public long userFacingConnectFailures() {
        return connectFailures.sum();
    }

    public long reuseHits() {
        return reuseHits.sum();
    }

    public long recoveryProbesOk() {
        return recoveryProbesOk.sum();
    }

    public long recoveryProbesFail() {
        return recoveryProbesFail.sum();
    }

    public double successRate() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) successfulCheckouts.sum() / total;
    }

    public double averageLatencyMs() {
        long total = totalRequests.sum();
        return total == 0 ? 0.0 : (double) totalCheckoutLatencyMs.get() / total;
    }

    /** Approximate p95 of recorded checkout latencies (simulated or wall). */
    public double p95LatencyMs() {
        List<Long> samples = new ArrayList<>(checkoutLatenciesMs);
        if (samples.isEmpty()) {
            return 0.0;
        }
        Collections.sort(samples);
        int idx = (int) Math.ceil(0.95 * samples.size()) - 1;
        return samples.get(Math.max(0, Math.min(idx, samples.size() - 1)));
    }

    public Map<String, Long> selectedEndpointCounts() {
        Map<String, Long> copy = new ConcurrentHashMap<>();
        selectedEndpointCounts.forEach((k, v) -> copy.put(k, v.sum()));
        return copy;
    }

    public String snapshot() {
        return "PoolMetrics{requests=" + totalRequests()
                + ", successRate=" + String.format("%.1f%%", successRate() * 100)
                + ", avgLatencyMs=" + String.format("%.1f", averageLatencyMs())
                + ", preemptiveFailovers=" + preemptiveFailovers()
                + ", warmHits=" + warmHits()
                + ", coldCreates=" + coldCreates()
                + ", preWarmEvents=" + preWarmEvents()
                + ", connectFailures=" + connectFailures()
                + ", physicalConnects=" + physicalConnects()
                + ", reuseHits=" + reuseHits()
                + ", recoveryProbesOk=" + recoveryProbesOk()
                + ", recoveryProbesFail=" + recoveryProbesFail()
                + ", selected=" + selectedEndpointCounts()
                + '}';
    }

    public void reset() {
        totalRequests.reset();
        successfulCheckouts.reset();
        failedCheckouts.reset();
        preemptiveFailovers.reset();
        primaryOk.reset();
        degradedMode.reset();
        warmHits.reset();
        coldCreates.reset();
        preWarmEvents.reset();
        connectFailures.reset();
        recoveryProbesOk.reset();
        recoveryProbesFail.reset();
        physicalConnects.reset();
        primaryConnectAttempts.reset();
        reuseHits.reset();
        totalCheckoutLatencyMs.set(0);
        checkoutLatenciesMs.clear();
        selectedEndpointCounts.clear();
    }
}
