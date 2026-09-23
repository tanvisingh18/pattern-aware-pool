package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.routing.RoutingDecision;

import java.sql.Connection;
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
    private volatile IdleConnectionPool owner;
    private volatile RoutingDecision routingDecision;

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
        }
    }

    public void setRoutingDecision(RoutingDecision routingDecision) {
        this.routingDecision = routingDecision;
    }

    public Optional<RoutingDecision> routingDecision() {
        return Optional.ofNullable(routingDecision);
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
