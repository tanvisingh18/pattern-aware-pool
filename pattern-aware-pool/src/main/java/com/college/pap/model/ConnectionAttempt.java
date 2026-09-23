package com.college.pap.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One recorded connection attempt — the atomic unit of the pool's "memory".
 * Layer 1 stores these; Layer 2/3 learn and act on them.
 */
public final class ConnectionAttempt {
    private final EndpointId endpointId;
    private final Instant timestamp;
    private final AttemptOutcome outcome;
    private final FailureType failureType;
    private final long durationMillis;

    public ConnectionAttempt(
            EndpointId endpointId,
            Instant timestamp,
            AttemptOutcome outcome,
            FailureType failureType,
            long durationMillis) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp");
        this.outcome = Objects.requireNonNull(outcome, "outcome");
        this.failureType = failureType == null ? FailureType.NONE : failureType;
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be >= 0");
        }
        this.durationMillis = durationMillis;

        if (outcome == AttemptOutcome.SUCCESS && this.failureType != FailureType.NONE) {
            throw new IllegalArgumentException("successful attempt cannot have a failure type");
        }
        if (outcome != AttemptOutcome.SUCCESS && this.failureType == FailureType.NONE) {
            throw new IllegalArgumentException("failed/timeout attempt needs a failure type");
        }
    }

    public static ConnectionAttempt success(EndpointId endpointId, Instant timestamp, long durationMillis) {
        return new ConnectionAttempt(endpointId, timestamp, AttemptOutcome.SUCCESS, FailureType.NONE, durationMillis);
    }

    public static ConnectionAttempt failure(
            EndpointId endpointId,
            Instant timestamp,
            AttemptOutcome outcome,
            FailureType failureType,
            long durationMillis) {
        if (outcome == AttemptOutcome.SUCCESS) {
            throw new IllegalArgumentException("use success(...) for successful attempts");
        }
        return new ConnectionAttempt(endpointId, timestamp, outcome, failureType, durationMillis);
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public AttemptOutcome outcome() {
        return outcome;
    }

    public FailureType failureType() {
        return failureType;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public boolean isFailure() {
        return outcome != AttemptOutcome.SUCCESS;
    }

    /** Hour-of-day in 0..23 for time-correlation bucketing. */
    public int hourOfDay() {
        return timestamp.atZone(java.time.ZoneOffset.UTC).getHour();
    }

    @Override
    public String toString() {
        return "ConnectionAttempt{"
                + "endpoint=" + endpointId
                + ", at=" + timestamp
                + ", outcome=" + outcome
                + ", failureType=" + failureType
                + ", durationMs=" + durationMillis
                + '}';
    }
}
