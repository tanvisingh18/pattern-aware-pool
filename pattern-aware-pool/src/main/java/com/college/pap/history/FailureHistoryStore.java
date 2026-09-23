package com.college.pap.history;

import com.college.pap.model.ConnectionAttempt;
import com.college.pap.model.EndpointId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Layer 1 — Failure Pattern Memory.
 *
 * Thread-safe circular buffer of recent connection attempts, keyed by endpoint.
 * No external DB: everything stays in memory so the pool can learn locally
 * even on flaky / offline field deployments.
 */
public final class FailureHistoryStore {

    private final int capacityPerEndpoint;
    private final Map<EndpointId, ConcurrentLinkedDeque<ConnectionAttempt>> byEndpoint =
            new ConcurrentHashMap<>();

    public FailureHistoryStore(int capacityPerEndpoint) {
        if (capacityPerEndpoint < 1) {
            throw new IllegalArgumentException("capacityPerEndpoint must be >= 1");
        }
        this.capacityPerEndpoint = capacityPerEndpoint;
    }

    /** Records one attempt. Oldest entries are dropped when capacity is exceeded. */
    public void record(ConnectionAttempt attempt) {
        Objects.requireNonNull(attempt, "attempt");
        ConcurrentLinkedDeque<ConnectionAttempt> buffer =
                byEndpoint.computeIfAbsent(attempt.endpointId(), id -> new ConcurrentLinkedDeque<>());
        buffer.addLast(attempt);
        while (buffer.size() > capacityPerEndpoint) {
            buffer.pollFirst();
        }
    }

    public List<ConnectionAttempt> getHistory(EndpointId endpointId) {
        Objects.requireNonNull(endpointId, "endpointId");
        ConcurrentLinkedDeque<ConnectionAttempt> buffer = byEndpoint.get(endpointId);
        if (buffer == null || buffer.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(new ArrayList<>(buffer));
    }

    public Set<EndpointId> knownEndpoints() {
        return Collections.unmodifiableSet(byEndpoint.keySet());
    }

    public int size(EndpointId endpointId) {
        ConcurrentLinkedDeque<ConnectionAttempt> buffer = byEndpoint.get(endpointId);
        return buffer == null ? 0 : buffer.size();
    }

    public int capacityPerEndpoint() {
        return capacityPerEndpoint;
    }

    public void clear(EndpointId endpointId) {
        byEndpoint.remove(endpointId);
    }

    public void clearAll() {
        byEndpoint.clear();
    }
}
