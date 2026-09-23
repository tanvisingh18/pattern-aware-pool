package com.college.pap.pool;

import com.college.pap.model.EndpointId;
import com.college.pap.model.FailureType;

/**
 * Creates connections to a single logical endpoint.
 * Implementations may be simulated (flaky) or JDBC-backed.
 */
public interface EndpointConnector {
    EndpointId endpointId();

    PapConnection connect() throws ConnectionFailedException;

    final class ConnectionFailedException extends Exception {
        private final FailureType failureType;
        private final boolean poolExhausted;
        private long simulatedLatencyMs;

        public ConnectionFailedException(String message, FailureType failureType) {
            this(message, failureType, false);
        }

        public ConnectionFailedException(String message, FailureType failureType, boolean poolExhausted) {
            super(message);
            this.failureType = failureType == null ? FailureType.UNKNOWN : failureType;
            this.poolExhausted = poolExhausted;
        }

        public FailureType failureType() {
            return failureType;
        }

        /** True when borrow timed out waiting for a permit — not an endpoint failure. */
        public boolean isPoolExhausted() {
            return poolExhausted;
        }

        public long simulatedLatencyMs() {
            return simulatedLatencyMs;
        }

        public void setSimulatedLatencyMs(long simulatedLatencyMs) {
            this.simulatedLatencyMs = Math.max(0, simulatedLatencyMs);
        }
    }
}
