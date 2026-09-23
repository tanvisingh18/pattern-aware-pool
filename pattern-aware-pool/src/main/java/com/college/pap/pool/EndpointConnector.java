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

        public ConnectionFailedException(String message, FailureType failureType) {
            super(message);
            this.failureType = failureType == null ? FailureType.UNKNOWN : failureType;
        }

        public FailureType failureType() {
            return failureType;
        }
    }
}
