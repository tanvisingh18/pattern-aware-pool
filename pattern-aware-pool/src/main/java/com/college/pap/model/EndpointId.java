package com.college.pap.model;

import java.util.Objects;

/**
 * Stable identifier for a logical database/API endpoint
 * (e.g. "primary-db", "backup-db", "clinic-cell-uplink").
 */
public final class EndpointId {
    private final String value;

    public EndpointId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("endpoint id must be non-blank");
        }
        this.value = value.trim();
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointId that)) {
            return false;
        }
        return value.equals(that.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
