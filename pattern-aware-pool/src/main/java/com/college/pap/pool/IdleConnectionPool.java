package com.college.pap.pool;

import com.college.pap.model.FailureType;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Semaphore;

/**
 * Per-endpoint idle connection pool. Caps concurrent checkouts with a
 * {@link Semaphore} and parks returned connections for reuse.
 */
public final class IdleConnectionPool {

    private final EndpointConnector connector;
    private final ConnectionValidator validator;
    private final int maxSize;
    private final Semaphore permits;
    private final ArrayDeque<PapConnection> idle = new ArrayDeque<>();
    private volatile boolean drained;

    public IdleConnectionPool(EndpointConnector connector, ConnectionValidator validator, int maxSize) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.validator = Objects.requireNonNull(validator, "validator");
        if (maxSize < 1) {
            throw new IllegalArgumentException("maxSize must be >= 1");
        }
        this.maxSize = maxSize;
        this.permits = new Semaphore(maxSize, true);
    }

    public int maxSize() {
        return maxSize;
    }

    public synchronized int idleCount() {
        return idle.size();
    }

    /**
     * Borrows an idle connection or creates a new one via the endpoint connector.
     * Blocks if {@code maxSize} connections are already checked out.
     */
    public PapConnection acquire() throws EndpointConnector.ConnectionFailedException {
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EndpointConnector.ConnectionFailedException(
                    "interrupted waiting for pool permit", FailureType.UNKNOWN);
        }
        try {
            PapConnection recycled = pollValidIdle();
            if (recycled != null) {
                return recycled;
            }
            return createNew();
        } catch (EndpointConnector.ConnectionFailedException e) {
            permits.release();
            throw e;
        } catch (RuntimeException e) {
            permits.release();
            throw e;
        }
    }

    /**
     * Claims a checkout permit and binds {@code connection} so {@link PapConnection#close()}
     * returns it to this idle pool (used for warm-pool handoff).
     */
    public void borrowExternal(PapConnection connection)
            throws EndpointConnector.ConnectionFailedException {
        Objects.requireNonNull(connection, "connection");
        try {
            permits.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EndpointConnector.ConnectionFailedException(
                    "interrupted waiting for pool permit", FailureType.UNKNOWN);
        }
        connection.bindOwner(this);
        connection.prepareForCheckout();
    }

    /**
     * Returns a connection to the idle deque if under capacity and still valid;
     * otherwise physically destroys it.
     */
    public void release(PapConnection connection) {
        Objects.requireNonNull(connection, "connection");
        boolean kept = false;
        synchronized (this) {
            if (!drained && idle.size() < maxSize && validator.validate(connection)) {
                idle.addLast(connection);
                kept = true;
            }
        }
        if (!kept) {
            connection.destroy();
        }
        permits.release();
    }

    public void drainAndClose() {
        drained = true;
        synchronized (this) {
            while (!idle.isEmpty()) {
                PapConnection c = idle.pollFirst();
                if (c != null) {
                    c.destroy();
                }
            }
        }
    }

    private PapConnection pollValidIdle() {
        synchronized (this) {
            while (!idle.isEmpty()) {
                PapConnection c = idle.pollFirst();
                if (c == null) {
                    continue;
                }
                c.prepareForCheckout();
                c.bindOwner(this);
                if (validator.validate(c)) {
                    return c;
                }
                c.destroy();
            }
        }
        return null;
    }

    private PapConnection createNew() throws EndpointConnector.ConnectionFailedException {
        PapConnection created = connector.connect();
        created.bindOwner(this);
        created.prepareForCheckout();
        return created;
    }
}
