package com.college.pap.model;

/**
 * Classifies why a connection attempt failed.
 * Used by PatternAnalyzer so different failure modes can be tracked separately later.
 */
public enum FailureType {
    NONE,
    NETWORK_UNREACHABLE,
    TIMEOUT,
    AUTH_FAILURE,
    SSL_RESET,
    UNKNOWN
}
