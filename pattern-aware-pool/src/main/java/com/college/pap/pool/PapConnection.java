package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.routing.RoutingDecision;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight pooled connection handle used by the pattern-aware pool.
 * Wraps either a simulated or real JDBC-backed resource.
 * When bound to an {@link IdleConnectionPool}, {@link #close()} returns the
 * connection for reuse; {@link #destroy()} always closes the underlying resource.
 */
public final class PapConnection implements AutoCloseable {
    private final EndpointId endpointId;
    private final Object nativeHandle;
    private final Runnable onClose;
    private final AtomicBoolean checkedOut = new AtomicBoolean(true);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);
    private final Instant createdAt;
    private final boolean preWarmed;
    private final boolean capturedAutoCommit;
    private final boolean capturedReadOnly;
    private final int capturedTransactionIsolation;
    private final boolean jdbcDefaultsCaptured;
    private volatile IdleConnectionPool owner;
    private volatile RoutingDecision routingDecision;
    private volatile long lastSimulatedLatencyMs;
    private volatile long idleSinceEpochMs = -1L;

    public PapConnection(EndpointId endpointId, Object nativeHandle, Runnable onClose, boolean preWarmed) {
        this(endpointId, nativeHandle, onClose, preWarmed, null);
    }

    public PapConnection(
            EndpointId endpointId,
            Object nativeHandle,
            Runnable onClose,
            boolean preWarmed,
            IdleConnectionPool owner) {
        this.endpointId = Objects.requireNonNull(endpointId, "endpointId");
        this.nativeHandle = nativeHandle;
        this.onClose = onClose == null ? () -> {} : onClose;
        this.preWarmed = preWarmed;
        this.owner = owner;
        this.createdAt = Instant.now();

        boolean autoCommit = true;
        boolean readOnly = false;
        int isolation = Connection.TRANSACTION_READ_COMMITTED;
        boolean captured = false;
        if (nativeHandle instanceof Connection jdbc) {
            try {
                autoCommit = jdbc.getAutoCommit();
                readOnly = jdbc.isReadOnly();
                isolation = jdbc.getTransactionIsolation();
                captured = true;
            } catch (SQLException ignored) {
                captured = false;
            }
        }
        this.capturedAutoCommit = autoCommit;
        this.capturedReadOnly = readOnly;
        this.capturedTransactionIsolation = isolation;
        this.jdbcDefaultsCaptured = captured;
    }

    public EndpointId endpointId() {
        return endpointId;
    }

    public Object nativeHandle() {
        return nativeHandle;
    }

    public boolean isPreWarmed() {
        return preWarmed;
    }

    public Instant createdAt() {
        return createdAt;
    }

    /** True while checked out to a caller and not physically destroyed. */
    public boolean isOpen() {
        return checkedOut.get() && !destroyed.get();
    }

    public boolean isValid() {
        return !destroyed.get();
    }

    public boolean isDestroyed() {
        return destroyed.get();
    }

    /** Binds this handle to an idle pool so {@link #close()} releases instead of destroying. */
    public void bindOwner(IdleConnectionPool owner) {
        this.owner = owner;
    }

    IdleConnectionPool owner() {
        return owner;
    }

    /** Marks the handle as checked out again (idle → borrow). */
    void prepareForCheckout() {
        if (!destroyed.get()) {
            checkedOut.set(true);
            idleSinceEpochMs = -1L;
        }
    }

    void markIdle(long epochMs) {
        idleSinceEpochMs = epochMs;
    }

    long idleSinceEpochMs() {
        return idleSinceEpochMs;
    }

    long idleMillis(long nowEpochMs) {
        long since = idleSinceEpochMs;
        if (since < 0) {
            return 0L;
        }
        return Math.max(0L, nowEpochMs - since);
    }

    /**
     * On release: if JDBC and autoCommit is false, rollback, restore autoCommit/readOnly/
     * transactionIsolation to creation defaults. Returns false if any step throws
     * (caller must destroy instead of returning to idle).
     */
    boolean resetJdbcStateAfterUse() {
        if (!(nativeHandle instanceof Connection jdbc) || !jdbcDefaultsCaptured) {
            return true;
        }
        try {
            if (!jdbc.getAutoCommit()) {
                jdbc.rollback();
                jdbc.setAutoCommit(true);
            }
            jdbc.setReadOnly(capturedReadOnly);
            jdbc.setTransactionIsolation(capturedTransactionIsolation);
            if (capturedAutoCommit != jdbc.getAutoCommit()) {
                jdbc.setAutoCommit(capturedAutoCommit);
            }
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    public void setRoutingDecision(RoutingDecision routingDecision) {
        this.routingDecision = routingDecision;
    }

    public Optional<RoutingDecision> routingDecision() {
        return Optional.ofNullable(routingDecision);
    }

    public void setLastSimulatedLatencyMs(long lastSimulatedLatencyMs) {
        this.lastSimulatedLatencyMs = Math.max(0, lastSimulatedLatencyMs);
    }

    /** Simulated connect latency (experiments); 0 when not using a flaky simulator. */
    public long lastSimulatedLatencyMs() {
        return lastSimulatedLatencyMs;
    }

    public Optional<Connection> unwrapJdbc() {
        if (nativeHandle instanceof Connection jdbc) {
            return Optional.of(jdbc);
        }
        return Optional.empty();
    }

    /**
     * Physically closes the underlying resource. Use when returning to the pool
     * is not appropriate (invalid, pool drained, over capacity).
     */
    public void destroy() {
        owner = null;
        checkedOut.set(false);
        idleSinceEpochMs = -1L;
        if (destroyed.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    @Override
    public void close() {
        if (!checkedOut.compareAndSet(true, false)) {
            return;
        }
        IdleConnectionPool pool = owner;
        if (pool != null) {
            pool.release(this);
        } else {
            destroy();
        }
    }

    @Override
    public String toString() {
        return "PapConnection{endpoint=" + endpointId
                + ", preWarmed=" + preWarmed
                + ", open=" + isOpen() + '}';
    }
}
