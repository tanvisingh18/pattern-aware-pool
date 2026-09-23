package com.college.pap.pool;

import com.college.pap.model.FailureType;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Consumer;

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
    private final BooleanSupplier reuseEnabled;
    private final LongSupplier borrowTimeoutMillis;
    private final LongSupplier validationIdleMillis;
    private final Consumer<FailureType> onBorrowValidationFailure;
    private final LongAdder physicalConnects = new LongAdder();
    private final LongAdder reuseHits = new LongAdder();
    private final LongAdder openPhysical = new LongAdder();
    private volatile boolean drained;

    public IdleConnectionPool(EndpointConnector connector, ConnectionValidator validator, int maxSize) {
        this(connector, validator, maxSize, () -> true, () -> 3000L, () -> 5000L, null);
    }

    public IdleConnectionPool(
            EndpointConnector connector,
            ConnectionValidator validator,
            int maxSize,
            BooleanSupplier reuseEnabled) {
        this(connector, validator, maxSize, reuseEnabled, () -> 3000L, () -> 5000L, null);
    }

    public IdleConnectionPool(
            EndpointConnector connector,
            ConnectionValidator validator,
            int maxSize,
            BooleanSupplier reuseEnabled,
            LongSupplier borrowTimeoutMillis,
            LongSupplier validationIdleMillis,
            Consumer<FailureType> onBorrowValidationFailure) {
        this.connector = Objects.requireNonNull(connector, "connector");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.reuseEnabled = Objects.requireNonNull(reuseEnabled, "reuseEnabled");
        this.borrowTimeoutMillis = Objects.requireNonNull(borrowTimeoutMillis, "borrowTimeoutMillis");
        this.validationIdleMillis = Objects.requireNonNull(validationIdleMillis, "validationIdleMillis");
        this.onBorrowValidationFailure = onBorrowValidationFailure;
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

    public long physicalConnects() {
        return physicalConnects.sum();
    }

    public long reuseHits() {
        return reuseHits.sum();
    }

    /** Currently open physical connections (checked out + idle, not destroyed). */
    public long openPhysicalCount() {
        return openPhysical.sum();
    }

    /**
     * Borrows an idle connection or creates a new one via the endpoint connector.
     * Waits up to {@code borrowTimeoutMillis}; on timeout throws pool-exhausted
     * without treating it as an endpoint failure.
     */
    public PapConnection acquire() throws EndpointConnector.ConnectionFailedException {
        claimPermit();
        try {
            if (reuseEnabled.getAsBoolean()) {
                PapConnection recycled = pollValidateIdleOutsideLock();
                if (recycled != null) {
                    reuseHits.increment();
                    return recycled;
                }
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
        claimPermit();
        connection.bindOwner(this);
        connection.prepareForCheckout();
        openPhysical.increment();
    }

    private void claimPermit() throws EndpointConnector.ConnectionFailedException {
        long timeout = Math.max(0L, borrowTimeoutMillis.getAsLong());
        try {
            boolean ok = permits.tryAcquire(timeout, TimeUnit.MILLISECONDS);
            if (!ok) {
                throw new EndpointConnector.ConnectionFailedException(
                        "pool exhausted", FailureType.UNKNOWN, true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EndpointConnector.ConnectionFailedException(
                    "interrupted waiting for pool permit", FailureType.UNKNOWN);
        }
    }

    /**
     * Returns a connection to the idle deque if under capacity and still valid;
     * otherwise physically destroys it. JDBC state is reset outside the lock.
     */
    public void release(PapConnection connection) {
        Objects.requireNonNull(connection, "connection");
        boolean keep = false;
        if (reuseEnabled.getAsBoolean() && !drained) {
            if (!connection.resetJdbcStateAfterUse()) {
                connection.destroy();
                openPhysical.decrement();
                permits.release();
                return;
            }
            // Light validity check outside lock (no SELECT for just-returned connections
            // that were actively used — only structural/destroyed checks here).
            if (!connection.isDestroyed() && connection.nativeHandle() != null) {
                synchronized (this) {
                    if (!drained && idle.size() < maxSize) {
                        connection.markIdle(System.currentTimeMillis());
                        idle.addLast(connection);
                        keep = true;
                    }
                }
            }
        }
        if (!keep) {
            connection.destroy();
            openPhysical.decrement();
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
                    openPhysical.decrement();
                }
            }
        }
    }

    /**
     * Poll candidate under lock, validate (SELECT 1) after releasing the lock
     * when idle longer than {@code validationIdleMillis}.
     */
    private PapConnection pollValidateIdleOutsideLock() {
        long validationThreshold = Math.max(0L, validationIdleMillis.getAsLong());
        while (true) {
            PapConnection candidate;
            boolean needsValidation;
            synchronized (this) {
                candidate = idle.pollFirst();
                if (candidate == null) {
                    return null;
                }
                long idleMs = candidate.idleMillis(System.currentTimeMillis());
                needsValidation = idleMs >= validationThreshold;
            }
            candidate.prepareForCheckout();
            candidate.bindOwner(this);
            if (!needsValidation) {
                return candidate;
            }
            if (validator.validate(candidate)) {
                return candidate;
            }
            // Broken idle connection — evict and try next idle candidate.
            if (onBorrowValidationFailure != null) {
                onBorrowValidationFailure.accept(FailureType.UNKNOWN);
            }
            candidate.destroy();
            openPhysical.decrement();
        }
    }

    private PapConnection createNew() throws EndpointConnector.ConnectionFailedException {
        PapConnection created = connector.connect();
        physicalConnects.increment();
        openPhysical.increment();
        created.bindOwner(this);
        created.prepareForCheckout();
        return created;
    }
}
