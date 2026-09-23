package com.college.pap.routing;

import com.college.pap.model.EndpointId;
import com.college.pap.prediction.RiskScore;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Result of one Layer-2 routing decision: which endpoint to use and why.
 */
public final class RoutingDecision {
    public enum Reason {
        /** Preferred/primary endpoint has acceptable risk. */
        PRIMARY_OK,
        /** Primary looked risky — silently failover to a healthier backup. */
        PREEMPTIVE_FAILOVER,
        /** All endpoints high-risk; pick the least-bad and continue degraded. */
        DEGRADED_MODE,
        /** Only one endpoint registered. */
        SINGLE_ENDPOINT
    }

    /** Which signal caused avoidance of the requested endpoint. */
    public enum Trigger {
        SCORE,
        HOT_HOUR,
        LIVE_CLUSTER,
        RESET_PREDICTED,
        NONE
    }

    private final EndpointId requested;
    private final EndpointId selected;
    private final Reason reason;
    private final Trigger trigger;
    private final RiskScore selectedScore;
    private final Map<EndpointId, RiskScore> allScores;

    public RoutingDecision(
            EndpointId requested,
            EndpointId selected,
            Reason reason,
            RiskScore selectedScore,
            Map<EndpointId, RiskScore> allScores) {
        this(requested, selected, reason, Trigger.NONE, selectedScore, allScores);
    }

    public RoutingDecision(
            EndpointId requested,
            EndpointId selected,
            Reason reason,
            Trigger trigger,
            RiskScore selectedScore,
            Map<EndpointId, RiskScore> allScores) {
        this.requested = Objects.requireNonNull(requested, "requested");
        this.selected = Objects.requireNonNull(selected, "selected");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.trigger = Objects.requireNonNull(trigger, "trigger");
        this.selectedScore = Objects.requireNonNull(selectedScore, "selectedScore");
        this.allScores = Collections.unmodifiableMap(new LinkedHashMap<>(allScores));
    }

    public EndpointId requested() {
        return requested;
    }

    public EndpointId selected() {
        return selected;
    }

    public Reason reason() {
        return reason;
    }

    public Trigger trigger() {
        return trigger;
    }

    public RiskScore selectedScore() {
        return selectedScore;
    }

    public Map<EndpointId, RiskScore> allScores() {
        return allScores;
    }

    public boolean rerouted() {
        return !requested.equals(selected);
    }

    @Override
    public String toString() {
        return "RoutingDecision{requested=" + requested
                + ", selected=" + selected
                + ", reason=" + reason
                + ", trigger=" + trigger
                + ", score=" + String.format("%.2f", selectedScore.score())
                + ", rerouted=" + rerouted()
                + '}';
    }
}
