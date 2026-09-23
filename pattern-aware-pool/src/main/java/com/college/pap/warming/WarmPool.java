package com.college.pap.warming;

import com.college.pap.model.EndpointId;
import com.college.pap.pool.PapConnection;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;

/** Parking lot for pre-warmed backup connections. */
public final class WarmPool {
    private final EndpointId endpointId;
    private final int capacity;
    private final ArrayDeque<PapConnection> idle = new ArrayDeque<>();

    public WarmPool(EndpointId endpointId, int capacity) {
        this.endpointId = Objects.requireNonNull(endpointId);
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be >= 1");
        }
        this.capacity = capacity;
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public synchronized int size() {
        return idle.size();
    }

    public synchronized int capacity() {
        return capacity;
    }

    public synchronized boolean offer(PapConnection connection) {
        Objects.requireNonNull(connection);
        if (!endpointId.equals(connection.endpointId())) {
            throw new IllegalArgumentException("wrong endpoint for warm pool");
        }
        if (idle.size() >= capacity) {
            connection.close();
            return false;
        }
        idle.addLast(connection);
        return true;
    }

    public synchronized Optional<PapConnection> poll() {
        while (!idle.isEmpty()) {
            PapConnection c = idle.pollFirst();
            if (c != null && c.isOpen()) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }

    public synchronized void drainAndClose() {
        while (!idle.isEmpty()) {
            PapConnection c = idle.pollFirst();
            if (c != null) {
                c.close();
            }
        }
    }
}
